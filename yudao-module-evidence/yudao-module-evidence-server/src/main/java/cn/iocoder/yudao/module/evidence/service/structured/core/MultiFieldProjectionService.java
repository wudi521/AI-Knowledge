package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用多字段投影；只消费 Domain Registry 注册能力。 */
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

        FilterExpression filter = SimpleStructuredFilterResolver.resolve(query, domainCode, fieldRegistry);
        Set<String> filterFields = new LinkedHashSet<>();
        collectFilterFields(filter, filterFields);

        List<FieldDefinition> detected = detectFields(query, domainCode);
        List<FieldDefinition> projections = detected.stream()
                .filter(field -> !filterFields.contains(field.getFieldCode()))
                .toList();
        // 如果排除过滤字段后不足两个输出字段，不抢占原单字段/其它结构化链路。
        if (projections.size() < 2) return notApplicable();

        if (containsContextReference(query) && (explicitEntityIds == null || explicitEntityIds.isEmpty())) {
            return new Result(State.CLARIFY, null, null, null, null,
                    "你提到了多个字段，但当前没有可复用的上一轮对象集合，请先明确要查询哪些对象。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE);
        }

        FieldDefinition anchor = projections.get(0);
        MetricDefinition metric = syntheticMetric(anchor, domainCode);
        metricRegistry.register(metric);
        List<String> projectionCodes = projections.stream().map(FieldDefinition::getFieldCode).toList();
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
                .projections(projectionCodes)
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .filterExpression(filter)
                .resolvedEntities(scope.getResolvedEntityIds())
                .build();

        StructuredQueryResult result = executor.execute(plan);
        if (result == null || result.isUnsupported()) {
            return new Result(State.UNANSWERABLE, plan, metric, result, null, null,
                    StructuredFailureReason.UNSUPPORTED_FIELD);
        }
        String answer = render(projections, result.getRows());
        return new Result(State.ANSWER, plan, metric, result, answer, null, null);
    }

    private List<FieldDefinition> detectFields(String query, String domainCode) {
        List<FieldDefinition> detected = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field == null || field.getAliases() == null) continue;
            boolean matched = field.getAliases().stream().filter(StrUtil::isNotBlank).anyMatch(query::contains);
            if (matched) detected.add(field);
        }
        detected.sort(java.util.Comparator.comparingInt(f -> firstPosition(query, f)));
        return detected;
    }

    private void collectFilterFields(FilterExpression expression, Set<String> out) {
        if (expression == null || expression.getType() == null) return;
        if (expression.getType() == FilterExpression.Type.CONDITION) {
            if (StrUtil.isNotBlank(expression.getFieldCode())) out.add(expression.getFieldCode());
            return;
        }
        if (expression.getChildren() != null) {
            for (FilterExpression child : expression.getChildren()) collectFilterFields(child, out);
        }
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
        if (rows == null || rows.isEmpty()) return "当前范围内没有符合条件的结构化数据。";
        StringBuilder sb = new StringBuilder("当前范围共 ").append(rows.size()).append(" 个对象：\n");
        int i = 1;
        for (StructuredQueryResult.Row row : rows) {
            sb.append(i++).append(". ").append(StrUtil.blankToDefault(row.getEntityName(), "对象" + row.getEntityId()));
            Map<String, String> values = row.getFields() == null ? Map.of() : row.getFields();
            List<String> pairs = new ArrayList<>();
            for (FieldDefinition field : fields) {
                pairs.add(displayName(field) + "=" + StrUtil.blankToDefault(values.get(field.getFieldCode()), "未提供"));
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
