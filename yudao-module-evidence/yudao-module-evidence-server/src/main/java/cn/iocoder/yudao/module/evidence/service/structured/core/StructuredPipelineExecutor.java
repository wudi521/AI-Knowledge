package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent V1.1 组合式结构化执行内核。
 *
 * <p>它只执行 Domain Schema 已声明的数据能力：字段读取/变换、过滤、去重、分组、聚合、排序、limit。
 * 用户语义不在这里枚举；领域数据访问仍由 DomainStructuredDataAdapter 提供。</p>
 */
@Component
public class StructuredPipelineExecutor {
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final List<DomainStructuredDataAdapter> adapters;
    private final StructuredValueEvaluator values;

    public StructuredPipelineExecutor(DomainFieldRegistry fieldRegistry,
                                      DomainMetricRegistry metricRegistry,
                                      List<DomainStructuredDataAdapter> adapters,
                                      StructuredValueEvaluator values) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
        this.values = values;
    }

    public StructuredPipelineResult execute(StructuredPipelinePlan plan) {
        String validation = validate(plan);
        if (validation != null) return StructuredPipelineResult.failure(validation);

        Set<String> requiredFields = referencedFields(plan);
        String sourceMetric = sourceMetric(plan);
        String sourceCode = StrUtil.isNotBlank(sourceMetric)
                ? sourceMetric : requiredFields.stream().findFirst().orElse(null);
        if (StrUtil.isBlank(sourceCode)) {
            return StructuredPipelineResult.failure("structured pipeline has no executable field or metric source");
        }
        DomainStructuredDataAdapter adapter = adapters.stream().filter(a -> a.supports(sourceCode)).findFirst().orElse(null);
        if (adapter == null) return StructuredPipelineResult.failure("no structured adapter supports source: " + sourceCode);

        String firstField = requiredFields.stream().findFirst().orElse(null);
        StructuredQueryPlan sourcePlan = StructuredQueryPlan.builder()
                .route("AGENT_PIPELINE")
                .queryType(QueryType.LIST)
                .domainCode(plan.getDomainCode())
                .entityType(plan.getEntityType())
                .scope(plan.getScope())
                .metricCode(sourceMetric == null ? sourceCode : sourceMetric)
                .fieldCode(firstField)
                .projections(new ArrayList<>(requiredFields))
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .build();

        StructuredQueryResult source = adapter.execute(sourcePlan);
        if (source == null || source.isUnsupported()) {
            return StructuredPipelineResult.failure(source == null ? "structured source returned null"
                    : StrUtil.blankToDefault(source.getUnsupportedReason(), "structured source unavailable"));
        }
        if (source.isTruncated()) return StructuredPipelineResult.failure("structured source is truncated; complete conclusion is unsafe");

        List<StructuredQueryResult.Row> sourceRows = source.getRows() == null ? List.of() : source.getRows();
        int sourceEntityCount = sourceRows.size();
        List<StructuredQueryResult.Row> filtered = new ArrayList<>();
        for (StructuredQueryResult.Row row : sourceRows) {
            if (plan.getFilter() == null || matches(plan.getDomainCode(), row, plan.getFilter())) filtered.add(row);
        }

        if (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty()) {
            return executeGrouped(plan, filtered, sourceEntityCount);
        }
        return executeFlat(plan, filtered, sourceEntityCount);
    }

    private StructuredPipelineResult executeFlat(StructuredPipelinePlan plan,
                                                  List<StructuredQueryResult.Row> rows,
                                                  int sourceEntityCount) {
        StructuredAggregateSpec aggregate = plan.getAggregate();
        if (aggregate != null) {
            AggregateValue computed = aggregate(plan.getDomainCode(), rows, aggregate);
            if (!computed.success()) return StructuredPipelineResult.failure(computed.message());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("aggregate", aggregate.operation().name());
            meta.put("completeDataset", true);
            meta.put("authoritativeEmpty", rows.isEmpty());
            return new StructuredPipelineResult(true, null, List.of(), computed.value(), true,
                    rows.isEmpty(), sourceEntityCount, computed.missing(), meta);
        }

        SortRows sorted = sortRows(plan.getDomainCode(), rows, plan.getOrderBy());
        if (!sorted.success()) return StructuredPipelineResult.failure(sorted.message());
        List<StructuredQueryResult.Row> working = sorted.rows();
        if (plan.isDistinct()) working = distinctRows(plan.getDomainCode(), working, plan.getSelect());
        if (plan.getLimit() != null && plan.getLimit() > 0 && working.size() > plan.getLimit()) {
            working = new ArrayList<>(working.subList(0, plan.getLimit()));
        }
        Projection projection = project(plan.getDomainCode(), working, plan.getSelect());
        if (!projection.success()) return StructuredPipelineResult.failure(projection.message());
        return new StructuredPipelineResult(true, null, projection.rows(), null, true,
                working.isEmpty(), sourceEntityCount, projection.missing(), Map.of(
                "completeDataset", true,
                "authoritativeEmpty", working.isEmpty(),
                "outputCount", projection.rows().size()));
    }

    private StructuredPipelineResult executeGrouped(StructuredPipelinePlan plan,
                                                     List<StructuredQueryResult.Row> rows,
                                                     int sourceEntityCount) {
        Map<String, GroupBucket> groups = new LinkedHashMap<>();
        int missing = 0;
        for (StructuredQueryResult.Row row : rows) {
            List<List<String>> dimensions = new ArrayList<>();
            boolean rowMissing = false;
            for (StructuredValueExpression expression : plan.getGroupBy()) {
                List<String> result = values.values(plan.getDomainCode(), row, expression);
                if (result.isEmpty()) { rowMissing = true; break; }
                dimensions.add(result);
            }
            if (rowMissing) { missing++; continue; }
            for (List<String> tuple : cartesian(dimensions)) {
                String key = String.join(" | ", tuple);
                groups.computeIfAbsent(key, k -> new GroupBucket(tuple)).rows.add(row);
            }
        }
        if (missing > 0) {
            return StructuredPipelineResult.failure("group-by field data is incomplete on " + missing
                    + " of " + rows.size() + " entities; cannot prove complete grouped result");
        }

        StructuredAggregateSpec aggregate = plan.getAggregate() == null
                ? new StructuredAggregateSpec(Operation.COUNT, null, null) : plan.getAggregate();
        List<StructuredPipelineResult.Row> output = new ArrayList<>();
        for (Map.Entry<String, GroupBucket> entry : groups.entrySet()) {
            AggregateValue computed = aggregate(plan.getDomainCode(), entry.getValue().rows, aggregate);
            if (!computed.success()) return StructuredPipelineResult.failure(computed.message());
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < plan.getGroupBy().size(); i++) {
                fields.put(expressionKey(plan.getGroupBy().get(i)), entry.getValue().keys.get(i));
            }
            output.add(new StructuredPipelineResult.Row(null, entry.getKey(), fields,
                    computed.value(), entry.getKey()));
        }

        GroupSort sorted = sortGroups(plan.getDomainCode(), output, plan.getOrderBy());
        if (!sorted.success()) return StructuredPipelineResult.failure(sorted.message());
        output = sorted.rows();
        if (plan.getLimit() != null && plan.getLimit() > 0 && output.size() > plan.getLimit()) {
            output = new ArrayList<>(output.subList(0, plan.getLimit()));
        }
        return new StructuredPipelineResult(true, null, output, null, true, output.isEmpty(),
                sourceEntityCount, 0, Map.of(
                "completeDataset", true,
                "authoritativeEmpty", output.isEmpty(),
                "groupCount", output.size(),
                "aggregate", aggregate.operation().name(),
                "outputCount", output.size()));
    }

    private AggregateValue aggregate(String domainCode, List<StructuredQueryResult.Row> rows,
                                     StructuredAggregateSpec spec) {
        Operation op = spec.operation();
        if (op == null || op == Operation.NONE) return AggregateValue.failure("aggregate operation is required");
        if (op == Operation.COUNT && spec.value() == null && StrUtil.isBlank(spec.metricCode())) {
            return AggregateValue.success((double) rows.size(), 0);
        }

        List<String> raw = new ArrayList<>();
        int missing = 0;
        String valueType = null;
        if (spec.value() != null) {
            valueType = values.outputType(domainCode, spec.value());
            for (StructuredQueryResult.Row row : rows) {
                List<String> item = values.values(domainCode, row, spec.value());
                if (item.isEmpty()) missing++;
                else raw.addAll(item);
            }
        } else if (StrUtil.isNotBlank(spec.metricCode())) {
            MetricDefinition metric = metricRegistry.lookup(domainCode, spec.metricCode()).orElse(null);
            if (metric == null) return AggregateValue.failure("metric is not registered: " + spec.metricCode());
            valueType = metric.getValueType();
            for (StructuredQueryResult.Row row : rows) {
                if (row.getValue() == null) missing++;
                else raw.add(String.valueOf(row.getValue()));
            }
        }

        if (op == Operation.COUNT) return AggregateValue.success((double) raw.size(), 0);
        if (missing > 0) return AggregateValue.failure("aggregate source is incomplete on " + missing
                + " of " + rows.size() + " entities");
        if (op == Operation.COUNT_DISTINCT) return AggregateValue.success((double) new LinkedHashSet<>(raw).size(), 0);
        if (raw.isEmpty()) return AggregateValue.success(0D, 0);
        if (!"INTEGER".equalsIgnoreCase(valueType) && !"DECIMAL".equalsIgnoreCase(valueType)) {
            return AggregateValue.failure("operation " + op + " requires numeric value but got " + valueType);
        }
        List<BigDecimal> numbers = new ArrayList<>();
        try { for (String value : raw) numbers.add(new BigDecimal(value)); }
        catch (NumberFormatException e) { return AggregateValue.failure("aggregate value is not numeric"); }
        return switch (op) {
            case SUM -> AggregateValue.success(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue(), 0);
            case AVG -> AggregateValue.success(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(numbers.size()), 8, java.math.RoundingMode.HALF_UP).doubleValue(), 0);
            case MIN -> AggregateValue.success(numbers.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO).doubleValue(), 0);
            case MAX -> AggregateValue.success(numbers.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).doubleValue(), 0);
            default -> AggregateValue.failure("unsupported aggregate operation: " + op);
        };
    }

    private SortRows sortRows(String domainCode, List<StructuredQueryResult.Row> rows,
                              List<StructuredOrderSpec> specs) {
        if (specs == null || specs.isEmpty()) return SortRows.success(new ArrayList<>(rows));
        Map<StructuredQueryResult.Row, List<String>> keys = new LinkedHashMap<>();
        for (StructuredQueryResult.Row row : rows) {
            List<String> rowKeys = new ArrayList<>();
            for (StructuredOrderSpec spec : specs) {
                if (spec.aggregateValue()) return SortRows.failure("aggregateValue ordering is only valid after GROUP BY");
                String key;
                if (spec.value() != null) {
                    List<String> result = values.values(domainCode, row, spec.value());
                    if (result.size() != 1) return SortRows.failure("order-by expression must yield exactly one value per entity: "
                            + expressionKey(spec.value()));
                    key = result.get(0);
                } else if (StrUtil.isNotBlank(spec.metricCode())) {
                    if (row.getValue() == null) return SortRows.failure("order-by metric is missing on entity " + row.getEntityId());
                    key = String.valueOf(row.getValue());
                } else return SortRows.failure("order-by source is missing");
                rowKeys.add(key);
            }
            keys.put(row, rowKeys);
        }
        List<StructuredQueryResult.Row> out = new ArrayList<>(rows);
        out.sort((a, b) -> compareOrderKeys(domainCode, a, b, keys, specs));
        return SortRows.success(out);
    }

    private int compareOrderKeys(String domainCode, StructuredQueryResult.Row a, StructuredQueryResult.Row b,
                                 Map<StructuredQueryResult.Row, List<String>> keys,
                                 List<StructuredOrderSpec> specs) {
        for (int i = 0; i < specs.size(); i++) {
            StructuredOrderSpec spec = specs.get(i);
            String type = spec.value() != null ? values.outputType(domainCode, spec.value()) : "DECIMAL";
            int cmp = values.compare(keys.get(a).get(i), keys.get(b).get(i), type);
            if (cmp != 0) return spec.direction() == SortDirection.ASC ? cmp : -cmp;
        }
        return Long.compare(a.getEntityId() == null ? Long.MAX_VALUE : a.getEntityId(),
                b.getEntityId() == null ? Long.MAX_VALUE : b.getEntityId());
    }

    private GroupSort sortGroups(String domainCode, List<StructuredPipelineResult.Row> rows,
                                 List<StructuredOrderSpec> specs) {
        if (specs == null || specs.isEmpty()) return GroupSort.success(new ArrayList<>(rows));
        List<StructuredPipelineResult.Row> out = new ArrayList<>(rows);
        for (StructuredOrderSpec spec : specs) {
            if (!spec.aggregateValue()) {
                return GroupSort.failure("grouped result currently orders by aggregateValue; field dimensions remain deterministic insertion order");
            }
        }
        out.sort((a, b) -> {
            for (StructuredOrderSpec spec : specs) {
                double av = a.value() == null ? 0D : a.value();
                double bv = b.value() == null ? 0D : b.value();
                int cmp = Double.compare(av, bv);
                if (cmp != 0) return spec.direction() == SortDirection.ASC ? cmp : -cmp;
            }
            return String.valueOf(a.groupKey()).compareTo(String.valueOf(b.groupKey()));
        });
        return GroupSort.success(out);
    }

    private Projection project(String domainCode, List<StructuredQueryResult.Row> rows,
                               List<StructuredValueExpression> select) {
        List<StructuredPipelineResult.Row> out = new ArrayList<>();
        int missing = 0;
        for (StructuredQueryResult.Row row : rows) {
            Map<String, String> fields = new LinkedHashMap<>();
            if (select == null || select.isEmpty()) {
                fields.putAll(row.getFields() == null ? Map.of() : row.getFields());
            } else {
                for (StructuredValueExpression expression : select) {
                    List<String> result = values.values(domainCode, row, expression);
                    if (result.isEmpty()) { missing++; fields.put(expressionKey(expression), null); }
                    else fields.put(expressionKey(expression), String.join("、", result));
                }
            }
            out.add(new StructuredPipelineResult.Row(row.getEntityId(), row.getEntityName(), fields,
                    row.getValue(), null));
        }
        return Projection.success(out, missing);
    }

    private List<StructuredQueryResult.Row> distinctRows(String domainCode,
                                                         List<StructuredQueryResult.Row> rows,
                                                         List<StructuredValueExpression> select) {
        Map<String, StructuredQueryResult.Row> unique = new LinkedHashMap<>();
        for (StructuredQueryResult.Row row : rows) {
            String key;
            if (select == null || select.isEmpty()) key = String.valueOf(row.getEntityId());
            else {
                List<String> tuple = new ArrayList<>();
                for (StructuredValueExpression expression : select) tuple.add(String.join("、", values.values(domainCode, row, expression)));
                key = String.join("\u001f", tuple);
            }
            unique.putIfAbsent(key, row);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean matches(String domainCode, StructuredQueryResult.Row row, StructuredPredicateNode node) {
        if (node == null || node.type() == null) return true;
        return switch (node.type()) {
            case AND -> node.children().stream().allMatch(child -> matches(domainCode, row, child));
            case OR -> !node.children().isEmpty() && node.children().stream().anyMatch(child -> matches(domainCode, row, child));
            case CONDITION -> {
                List<String> actual = values.values(domainCode, row, node.value());
                String type = values.outputType(domainCode, node.value());
                yield values.matches(node.operator(), actual, node.expected(), type);
            }
        };
    }

    private String validate(StructuredPipelinePlan plan) {
        if (plan == null || StrUtil.isBlank(plan.getDomainCode()) || plan.getScope() == null
                || plan.getScope().getCurrentKbId() == null) return "pipeline scope/domain is incomplete";
        if (plan.getLimit() != null && (plan.getLimit() < 1 || plan.getLimit() > 50)) return "limit must be 1..50";
        for (StructuredValueExpression expression : allExpressions(plan)) {
            StructuredValueEvaluator.Validation v = values.validate(plan.getDomainCode(), expression);
            if (!v.valid()) return v.message();
        }
        String predicateError = validatePredicate(plan.getDomainCode(), plan.getFilter());
        if (predicateError != null) return predicateError;
        for (StructuredValueExpression group : safe(plan.getGroupBy())) {
            FieldDefinition field = fieldRegistry.byCode(plan.getDomainCode(), group.fieldCode()).orElse(null);
            if (field == null || !field.isGroupable()) return "field is not groupable: " + group.fieldCode();
        }
        for (StructuredOrderSpec order : safe(plan.getOrderBy())) {
            if (order.aggregateValue()) continue;
            if (order.value() != null) {
                FieldDefinition field = fieldRegistry.byCode(plan.getDomainCode(), order.value().fieldCode()).orElse(null);
                if (field == null || !field.isSortable()) return "field is not sortable: " + order.value().fieldCode();
            } else if (StrUtil.isNotBlank(order.metricCode())) {
                if (metricRegistry.lookup(plan.getDomainCode(), order.metricCode()).isEmpty()) return "metric is not registered: " + order.metricCode();
            } else return "order-by source is missing";
        }
        StructuredAggregateSpec aggregate = plan.getAggregate();
        if (aggregate != null) {
            Operation op = aggregate.operation();
            if (op == null || op == Operation.NONE) return "aggregate operation is invalid";
            if (StrUtil.isNotBlank(aggregate.metricCode())) {
                MetricDefinition metric = metricRegistry.lookup(plan.getDomainCode(), aggregate.metricCode()).orElse(null);
                if (metric == null) return "metric is not registered: " + aggregate.metricCode();
                if (op != Operation.COUNT && (metric.getSupportedOperations() == null || !metric.getSupportedOperations().contains(op))) {
                    return "operation " + op + " is not supported by metric " + metric.getMetricCode();
                }
            }
        }
        return null;
    }

    private String validatePredicate(String domainCode, StructuredPredicateNode node) {
        if (node == null) return null;
        if (node.type() == StructuredPredicateNode.Type.AND || node.type() == StructuredPredicateNode.Type.OR) {
            if (node.children().isEmpty()) return "boolean filter group must contain children";
            for (StructuredPredicateNode child : node.children()) {
                String error = validatePredicate(domainCode, child);
                if (error != null) return error;
            }
            return null;
        }
        if (node.value() == null || node.operator() == null) return "filter condition requires value/operator";
        FieldDefinition field = fieldRegistry.byCode(domainCode, node.value().fieldCode()).orElse(null);
        if (field == null || !field.isFilterable()) return "field is not filterable: " + node.value().fieldCode();
        if (node.value().transforms().isEmpty()) {
            if (field.getAllowedOperators() == null || !field.getAllowedOperators().contains(node.operator())) {
                return "operator " + node.operator() + " is not allowed for field " + field.getFieldCode();
            }
        } else if (!operatorCompatible(node.operator(), values.outputType(domainCode, node.value()))) {
            return "operator " + node.operator() + " is not compatible with transformed value " + expressionKey(node.value());
        }
        if (node.operator() != FilterOperator.EXISTS && node.expected().isEmpty()) return "filter values are required";
        return null;
    }

    private boolean operatorCompatible(FilterOperator operator, String type) {
        if (operator == FilterOperator.EXISTS || operator == FilterOperator.EQ || operator == FilterOperator.NE || operator == FilterOperator.IN) return true;
        if ("STRING".equalsIgnoreCase(type)) return operator == FilterOperator.CONTAINS || operator == FilterOperator.STARTS_WITH;
        return operator == FilterOperator.GT || operator == FilterOperator.GTE || operator == FilterOperator.LT
                || operator == FilterOperator.LTE || operator == FilterOperator.BETWEEN;
    }

    private Set<String> referencedFields(StructuredPipelinePlan plan) {
        Set<String> fields = new LinkedHashSet<>();
        for (StructuredValueExpression expression : allExpressions(plan)) if (expression != null && StrUtil.isNotBlank(expression.fieldCode())) fields.add(expression.fieldCode());
        collectPredicateFields(plan.getFilter(), fields);
        return fields;
    }

    private List<StructuredValueExpression> allExpressions(StructuredPipelinePlan plan) {
        List<StructuredValueExpression> out = new ArrayList<>();
        out.addAll(safe(plan.getSelect()));
        out.addAll(safe(plan.getGroupBy()));
        if (plan.getAggregate() != null && plan.getAggregate().value() != null) out.add(plan.getAggregate().value());
        for (StructuredOrderSpec order : safe(plan.getOrderBy())) if (order.value() != null) out.add(order.value());
        collectPredicateExpressions(plan.getFilter(), out);
        return out;
    }

    private void collectPredicateExpressions(StructuredPredicateNode node, List<StructuredValueExpression> out) {
        if (node == null) return;
        if (node.type() == StructuredPredicateNode.Type.CONDITION && node.value() != null) out.add(node.value());
        for (StructuredPredicateNode child : node.children()) collectPredicateExpressions(child, out);
    }

    private void collectPredicateFields(StructuredPredicateNode node, Set<String> out) {
        if (node == null) return;
        if (node.value() != null && StrUtil.isNotBlank(node.value().fieldCode())) out.add(node.value().fieldCode());
        for (StructuredPredicateNode child : node.children()) collectPredicateFields(child, out);
    }

    private String sourceMetric(StructuredPipelinePlan plan) {
        if (plan.getAggregate() != null && StrUtil.isNotBlank(plan.getAggregate().metricCode())) return plan.getAggregate().metricCode();
        for (StructuredOrderSpec order : safe(plan.getOrderBy())) if (StrUtil.isNotBlank(order.metricCode())) return order.metricCode();
        return null;
    }

    private List<List<String>> cartesian(List<List<String>> dimensions) {
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<String> dimension : dimensions) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : result) for (String value : dimension) {
                List<String> tuple = new ArrayList<>(prefix); tuple.add(value); next.add(tuple);
            }
            result = next;
        }
        return result;
    }

    private String expressionKey(StructuredValueExpression expression) {
        StringBuilder sb = new StringBuilder(expression.fieldCode());
        if (expression.explode()) sb.append("|EXPLODE");
        for (StructuredValueTransform transform : expression.transforms()) sb.append('|').append(transform.name());
        return sb.toString();
    }

    private <T> List<T> safe(Collection<T> source) { return source == null ? List.of() : List.copyOf(source); }

    private static final class GroupBucket {
        private final List<String> keys;
        private final List<StructuredQueryResult.Row> rows = new ArrayList<>();
        private GroupBucket(List<String> keys) { this.keys = List.copyOf(keys); }
    }

    private record AggregateValue(boolean success, Double value, int missing, String message) {
        static AggregateValue success(Double value, int missing) { return new AggregateValue(true, value, missing, null); }
        static AggregateValue failure(String message) { return new AggregateValue(false, null, 0, message); }
    }
    private record SortRows(boolean success, List<StructuredQueryResult.Row> rows, String message) {
        static SortRows success(List<StructuredQueryResult.Row> rows) { return new SortRows(true, rows, null); }
        static SortRows failure(String message) { return new SortRows(false, List.of(), message); }
    }
    private record GroupSort(boolean success, List<StructuredPipelineResult.Row> rows, String message) {
        static GroupSort success(List<StructuredPipelineResult.Row> rows) { return new GroupSort(true, rows, null); }
        static GroupSort failure(String message) { return new GroupSort(false, List.of(), message); }
    }
    private record Projection(boolean success, List<StructuredPipelineResult.Row> rows, int missing, String message) {
        static Projection success(List<StructuredPipelineResult.Row> rows, int missing) { return new Projection(true, rows, missing, null); }
        static Projection failure(String message) { return new Projection(false, List.of(), 0, message); }
    }
}
