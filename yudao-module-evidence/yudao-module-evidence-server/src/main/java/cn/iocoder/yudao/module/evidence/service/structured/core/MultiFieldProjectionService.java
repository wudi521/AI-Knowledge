package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用多字段投影(CQ Multi Projection)。
 * <p>
 * 仅当一句查询同时命中 >=2 个 DomainFieldRegistry 已注册字段时接管；单字段保持旧链。
 * Core 不包含任何 Patent/Telecom/Manufacturing 字段名。
 */
@Slf4j
@Component
public class MultiFieldProjectionService {

    public enum State { NOT_APPLICABLE, ANSWER, CLARIFY, UNANSWERABLE }

    public record Result(State state, StructuredQueryPlan plan, MetricDefinition anchorMetric,
                         StructuredQueryResult result, String answer, String clarificationQuestion,
                         String reasonCode) {}

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final DomainEntityRegistry entityRegistry;
    private final StructuredQueryExecutor executor;

    public MultiFieldProjectionService(DomainFieldRegistry fieldRegistry,
                                       DomainMetricRegistry metricRegistry,
                                       DomainEntityRegistry entityRegistry,
                                       StructuredQueryExecutor executor) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.entityRegistry = entityRegistry;
        this.executor = executor;
    }

    public Result tryHandle(String query, Long kbId, String domainCode, List<Long> explicitEntityIds) {
        if (StrUtil.isBlank(query) || kbId == null || StrUtil.isBlank(domainCode)) return notApplicable();
        List<FieldDefinition> fields = detectFields(query, domainCode);
        if (fields.size() < 2) return notApplicable();

        if (containsContextReference(query) && (explicitEntityIds == null || explicitEntityIds.isEmpty())) {
            return new Result(State.CLARIFY, null, null, null, null,
                    "你提到了多个字段，但当前没有可复用的上一轮对象集合，请先明确要查询哪些对象。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE);
        }

        FieldDefinition anchor = fields.get(0);
        MetricDefinition metric = syntheticMetric(anchor, domainCode);
        metricRegistry.register(metric);
        List<String> projections = fields.stream().map(FieldDefinition::getFieldCode).toList();
        QueryScope scope = explicitEntityIds != null && !explicitEntityIds.isEmpty()
                ? QueryScope.documentSet(kbId, explicitEntityIds)
                : QueryScope.currentKb(kbId);
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY")
                .queryType(QueryType.LIST)
                .domainCode(domainCode)
                .entityType(anchor.getEntityType())
                .scope(scope)
                .metricCode(anchor.getFieldCode())
                .fieldCode(anchor.getFieldCode())
                .projections(projections)
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .resolvedEntities(scope.getResolvedEntityIds())
                .build();

        StructuredQueryResult result = executor.execute(plan);
        if (result == null || result.isUnsupported()) {
            return new Result(State.UNANSWERABLE, plan, metric, result, null, null,
                    StructuredFailureReason.UNSUPPORTED_FIELD);
        }
        String answer = render(fields, result.getRows());
        return new Result(State.ANSWER, plan, metric, result, answer, null, null);
    }

    private List<FieldDefinition> detectFields(String query, String domainCode) {
        List<FieldDefinition> detected = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field == null || field.getAliases() == null) continue;
            boolean matched = field.getAliases().stream().filter(StrUtil::isNotBlank).anyMatch(query::contains);
            if (matched) detected.add(field);
        }
        // Registry 为 ConcurrentHashMap，all 顺序不稳定；按首次别名在 query 中出现位置排序，保证计划可复现。
        detected.sort(java.util.Comparator.comparingInt(f -> firstPosition(query, f)));
        return detected;
    }

    private int firstPosition(String query, FieldDefinition field) {
        int best = Integer.MAX_VALUE;
        for (String alias : field.getAliases()) {
            if (StrUtil.isBlank(alias)) continue;
            int p = query.indexOf(alias);
            if (p >= 0) best = Math.min(best, p);
        }
        return best;
    }

    private boolean containsContextReference(String query) {
        return StrUtil.containsAny(query, "这些", "它们", "上述", "前面", "刚才", "这几个", "这几件", "这四个", "这三个", "这两个");
    }

    private MetricDefinition syntheticMetric(FieldDefinition field, String domainCode) {
        return MetricDefinition.builder()
                .metricCode(field.getFieldCode())
                .domainCode(domainCode)
                .entityType(field.getEntityType())
                .valueType(field.getValueType())
                .supportedOperations(java.util.Set.of())
                .adapterKey(domainCode)
                .aliases(field.getAliases())
                .displayName(displayName(field))
                .build();
    }

    private String render(List<FieldDefinition> fields, List<StructuredQueryResult.Row> rows) {
        if (rows == null || rows.isEmpty()) return "当前范围内没有可返回的结构化字段数据。";
        StringBuilder sb = new StringBuilder("当前范围共 ").append(rows.size()).append(" 个对象：\n");
        int i = 1;
        for (StructuredQueryResult.Row row : rows) {
            sb.append(i++).append(". ").append(StrUtil.blankToDefault(row.getEntityName(), "对象" + row.getEntityId()));
            Map<String, String> values = row.getFields() == null ? Map.of() : row.getFields();
            List<String> pairs = new ArrayList<>();
            for (FieldDefinition field : fields) {
                String value = values.get(field.getFieldCode());
                pairs.add(displayName(field) + "=" + StrUtil.blankToDefault(value, "未提供"));
            }
            sb.append("：").append(String.join("；", pairs)).append('\n');
        }
        return sb.toString().trim();
    }

    private String displayName(FieldDefinition field) {
        if (field.getAliases() != null && !field.getAliases().isEmpty() && StrUtil.isNotBlank(field.getAliases().get(0))) {
            return field.getAliases().get(0);
        }
        return field.getFieldCode();
    }

    private Result notApplicable() {
        return new Result(State.NOT_APPLICABLE, null, null, null, null, null, null);
    }
}
