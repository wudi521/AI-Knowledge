package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用文本字段相近值能力。
 *
 * <p>它不认识“专利名称”这类业务 Intent，只消费 DomainFieldRegistry 已注册的 STRING 字段。
 * 因而标题、产品名、合同名、知识标题等领域字段都可复用。全集结论只在 Structured Executor
 * 明确返回完整数据集且目标字段对全集均有值时产生；数据源截断或字段缺失时 fail-closed。</p>
 */
@Component
public class SimilarFieldValuesCapability implements KnowledgeCapability {
    public static final String NAME = "similar_field_values";
    private static final int MAX_ENTITIES = 2_000;
    private static final double DEFAULT_THRESHOLD = 0.58D;
    private static final int DEFAULT_TOP_N = 20;

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final StructuredQueryExecutor structuredExecutor;

    public SimilarFieldValuesCapability(DomainFieldRegistry fieldRegistry,
                                        DomainMetricRegistry metricRegistry,
                                        StructuredQueryExecutor structuredExecutor) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.structuredExecutor = structuredExecutor;
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "1",
                "在当前知识库完整实体范围内，对一个已注册文本字段寻找值彼此相近的对象组合。field 可传字段 code 或自然语言别名（如 标题/专利名称）；适合回答‘库里有没有名称相近的对象’这类集合关系问题。",
                Set.of("field"), true, 8_000L, 50);
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || StrUtil.isBlank(context.domainCode())) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "knowledge scope is incomplete");
        }
        String requestedField = String.valueOf(arguments.getOrDefault("field", "")).trim();
        FieldDefinition field = resolveField(context.domainCode(), requestedField);
        if (field == null || !"STRING".equalsIgnoreCase(field.getValueType())) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL,
                    "field is not a registered text field: " + requestedField);
        }

        MetricDefinition metric = MetricDefinition.builder()
                .metricCode(field.getFieldCode())
                .domainCode(context.domainCode())
                .entityType(field.getEntityType())
                .valueType(field.getValueType())
                .supportedOperations(Set.of())
                .adapterKey(context.domainCode())
                .aliases(field.getAliases())
                .displayName(displayName(field))
                .build();
        metricRegistry.register(metric);

        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("AGENT_CAPABILITY")
                .queryType(QueryType.LIST)
                .domainCode(context.domainCode())
                .entityType(field.getEntityType())
                .scope(QueryScope.currentKb(context.kbId()))
                .metricCode(field.getFieldCode())
                .fieldCode(field.getFieldCode())
                .projections(List.of(field.getFieldCode()))
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .build();

        StructuredQueryResult result = structuredExecutor.execute(plan);
        if (result == null || result.isUnsupported() || result.isTruncated()) {
            String reason = result == null ? "structured source returned no result"
                    : StrUtil.blankToDefault(result.getUnsupportedReason(), "structured source is incomplete");
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE, reason);
        }
        List<StructuredQueryResult.Row> rows = result.getRows() == null ? List.of() : result.getRows();
        if (rows.size() > MAX_ENTITIES) {
            return CapabilityResult.failure(AgentStopReason.CAPABILITY_UNAVAILABLE,
                    "current scope has " + rows.size() + " entities; online pair similarity limit is " + MAX_ENTITIES
                            + ", use an indexed similarity capability for this scale");
        }

        long missingValueCount = rows.stream().filter(row -> row == null || row.getFields() == null
                || StrUtil.isBlank(row.getFields().get(field.getFieldCode()))).count();
        if (missingValueCount > 0) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "field data is incomplete: " + field.getFieldCode() + " missing on " + missingValueCount
                            + " of " + rows.size() + " entities; cannot prove a collection-wide similarity conclusion");
        }

        double threshold = threshold(arguments.get("threshold"));
        int topN = topN(arguments.get("topN"));
        List<Item> items = toItems(rows, field.getFieldCode());
        List<SimilarPair> pairs = findPairs(items, threshold, topN);
        Output output = new Output(field.getFieldCode(), displayName(field), items.size(), threshold, pairs);
        return CapabilityResult.success(output, Map.of(
                "fieldCode", field.getFieldCode(),
                "entityCount", items.size(),
                "pairCount", pairs.size(),
                "threshold", threshold,
                "completeDataset", true,
                "missingValueCount", 0
        ));
    }

    private FieldDefinition resolveField(String domainCode, String requested) {
        if (StrUtil.isBlank(requested)) return null;
        String normalized = requested.trim();
        List<FieldDefinition> exact = fieldRegistry.all(domainCode).stream()
                .filter(f -> f != null && (f.getFieldCode().equalsIgnoreCase(normalized)
                        || (f.getAliases() != null && f.getAliases().stream()
                        .filter(StrUtil::isNotBlank).anyMatch(a -> a.equalsIgnoreCase(normalized)))))
                .toList();
        if (exact.size() == 1) return exact.get(0);

        List<FieldDefinition> fuzzy = fieldRegistry.all(domainCode).stream()
                .filter(f -> f != null && f.getAliases() != null && f.getAliases().stream()
                        .filter(StrUtil::isNotBlank)
                        .anyMatch(a -> a.contains(normalized) || normalized.contains(a)))
                .toList();
        return fuzzy.size() == 1 ? fuzzy.get(0) : null;
    }

    private List<Item> toItems(List<StructuredQueryResult.Row> rows, String fieldCode) {
        List<Item> out = new ArrayList<>();
        for (StructuredQueryResult.Row row : rows) {
            if (row == null) continue;
            String raw = row.getFields() == null ? null : row.getFields().get(fieldCode);
            if (StrUtil.isBlank(raw)) continue;
            String normalized = normalize(raw);
            if (normalized.isEmpty()) continue;
            out.add(new Item(row.getEntityId(), row.getEntityName(), raw.trim(), normalized, grams(normalized)));
        }
        return out;
    }

    private List<SimilarPair> findPairs(List<Item> items, double threshold, int topN) {
        List<SimilarPair> pairs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item a = items.get(i);
            for (int j = i + 1; j < items.size(); j++) {
                Item b = items.get(j);
                double score = dice(a.grams(), b.grams());
                if (score + 1e-9 < threshold) continue;
                pairs.add(new SimilarPair(a.entityId(), a.entityName(), a.value(),
                        b.entityId(), b.entityName(), b.value(), round(score)));
            }
        }
        pairs.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return pairs.stream().limit(topN).toList();
    }

    private Set<String> grams(String value) {
        Set<String> grams = new HashSet<>();
        if (value.length() <= 2) {
            grams.add(value);
            return grams;
        }
        for (int i = 0; i + 2 <= value.length(); i++) grams.add(value.substring(i, i + 2));
        return grams;
    }

    private double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0D;
        int common = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        for (String gram : smaller) if (larger.contains(gram)) common++;
        return (2D * common) / (a.size() + b.size());
    }

    private double threshold(Object raw) {
        double value = DEFAULT_THRESHOLD;
        if (raw instanceof Number n) value = n.doubleValue();
        else if (raw != null) {
            try { value = Double.parseDouble(String.valueOf(raw)); } catch (Exception ignore) { }
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private int topN(Object raw) {
        int value = DEFAULT_TOP_N;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) {
            try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        }
        return Math.max(1, Math.min(50, value));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private String displayName(FieldDefinition field) {
        if (field.getAliases() != null && !field.getAliases().isEmpty() && StrUtil.isNotBlank(field.getAliases().get(0))) {
            return field.getAliases().get(0);
        }
        return field.getFieldCode();
    }

    private record Item(Long entityId, String entityName, String value, String normalized, Set<String> grams) { }

    public record SimilarPair(Long leftEntityId, String leftEntityName, String leftValue,
                              Long rightEntityId, String rightEntityName, String rightValue,
                              double similarity) { }

    public record Output(String fieldCode, String fieldName, int entityCount,
                         double threshold, List<SimilarPair> pairs) {
        public String summary() {
            return "field=" + fieldCode + "; entityCount=" + entityCount + "; threshold=" + threshold
                    + "; pairs=" + pairs;
        }

        public String progressHash() {
            return fieldCode + ":" + entityCount + ":" + threshold + ":" + pairs.hashCode();
        }

        public String directAnswer() {
            String rule = "按当前文本相似度规则（字符二元组 Dice，相似度阈值 ≥ "
                    + String.format(Locale.ROOT, "%.2f", threshold) + "）";
            if (pairs == null || pairs.isEmpty()) {
                return rule + "，当前知识库完整范围内没有发现" + fieldName + "相近的对象组合。";
            }
            StringBuilder sb = new StringBuilder(rule).append("，发现 ").append(pairs.size())
                    .append(" 组").append(fieldName).append("相近的对象：\n");
            int i = 1;
            for (SimilarPair pair : pairs) {
                sb.append(i++).append(". “").append(pair.leftValue()).append("” ↔ “")
                        .append(pair.rightValue()).append("”（相似度 ")
                        .append(String.format(Locale.ROOT, "%.3f", pair.similarity())).append("）\n");
            }
            return sb.toString().trim();
        }
    }
}
