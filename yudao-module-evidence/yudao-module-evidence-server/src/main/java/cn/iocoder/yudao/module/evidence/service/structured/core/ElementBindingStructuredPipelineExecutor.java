package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化 Pipeline 的结果契约增强层。
 *
 * <p>一方面为 multi-value filter/projection 提供 element binding；另一方面在统一执行边界发布
 * resultShape / scalarValue / dataGrain，使 Planner、派生运算和 Evaluator 不需要猜结果到底是
 * 标量、分组还是实体行，也不需要从业务文案猜“物理记录/逻辑实体”粒度。</p>
 *
 * <p>正式运行还会先尝试整计划 Pushdown。只有明确 SUCCEEDED 才直接采用权威后端结果；
 * UNSUPPORTED 回退原 JVM Pipeline；FAILED 必须 fail-closed。</p>
 */
@Primary
@Component
public class ElementBindingStructuredPipelineExecutor extends StructuredPipelineExecutor {

    private final DomainMetricRegistry metricRegistry;
    private final StructuredValueEvaluator values;
    private final StructuredPushdownCoordinator pushdownCoordinator;

    @Autowired
    public ElementBindingStructuredPipelineExecutor(DomainFieldRegistry fieldRegistry,
                                                    DomainMetricRegistry metricRegistry,
                                                    List<DomainStructuredDataAdapter> adapters,
                                                    StructuredValueEvaluator values,
                                                    StructuredPushdownCoordinator pushdownCoordinator) {
        super(fieldRegistry, metricRegistry, adapters, values);
        this.metricRegistry = metricRegistry;
        this.values = values;
        this.pushdownCoordinator = pushdownCoordinator;
    }

    /** 迁移期纯 Java单测兼容：没有 Coordinator 时保持原 JVM 执行语义。 */
    public ElementBindingStructuredPipelineExecutor(DomainFieldRegistry fieldRegistry,
                                                    DomainMetricRegistry metricRegistry,
                                                    List<DomainStructuredDataAdapter> adapters,
                                                    StructuredValueEvaluator values) {
        super(fieldRegistry, metricRegistry, adapters, values);
        this.metricRegistry = metricRegistry;
        this.values = values;
        this.pushdownCoordinator = null;
    }

    @Override
    public StructuredPipelineResult execute(StructuredPipelinePlan plan) {
        if (plan != null && plan.getHaving() != null
                && (plan.getGroupBy() == null || plan.getGroupBy().isEmpty())) {
            return enrichResultContract(plan,
                    StructuredPipelineResult.failure("HAVING requires GROUP BY"));
        }

        StructuredPipelineResult result = tryPushdown(plan);
        if (result == null) {
            if (plan != null && plan.getHaving() != null) {
                result = executeHaving(plan);
            } else {
                result = requiresElementBinding(plan)
                        ? executeElementBound(plan)
                        : super.execute(plan);
            }
        }
        return enrichResultContract(plan, result);
    }

    private StructuredPipelineResult tryPushdown(StructuredPipelinePlan plan) {
        if (pushdownCoordinator == null) return null;
        StructuredPushdownResult pushed = pushdownCoordinator.execute(plan);
        if (pushed == null || pushed.status() == StructuredPushdownResult.Status.UNSUPPORTED) return null;
        if (pushed.status() == StructuredPushdownResult.Status.FAILED) {
            return StructuredPipelineResult.failure(
                    StrUtil.blankToDefault(pushed.reason(), "structured pushdown failed"),
                    Map.of("pushdownFailed", true));
        }
        return pushed.result();
    }

    /**
     * JVM fallback 的 HAVING 执行。先取消最终 limit 得到完整分组，再按聚合值过滤，最后才排序结果限制。
     * Pushdown 实现若能完整执行 HAVING，会在本方法之前直接返回，因此这里不会妨碍后端优化。
     */
    private StructuredPipelineResult executeHaving(StructuredPipelinePlan plan) {
        StructuredPipelinePlan unbounded = copyForHaving(plan);
        StructuredPipelineResult base = super.execute(unbounded);
        if (!base.success()) return base;
        if (base.scalarValue() != null) {
            return StructuredPipelineResult.failure("HAVING requires grouped aggregate rows");
        }

        List<StructuredPipelineResult.Row> filtered = new ArrayList<>();
        for (StructuredPipelineResult.Row row : base.rows()) {
            if (row == null || row.value() == null) {
                return StructuredPipelineResult.failure("grouped aggregate row is missing aggregateValue");
            }
            if (matchesHaving(row.value(), plan.getHaving())) filtered.add(row);
        }

        int preHavingGroupCount = base.rows().size();
        int fullGroupCount = filtered.size();
        boolean limited = plan.getLimit() != null && plan.getLimit() > 0 && filtered.size() > plan.getLimit();
        List<StructuredPipelineResult.Row> output = limited
                ? new ArrayList<>(filtered.subList(0, plan.getLimit()))
                : filtered;

        Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("havingApplied", true);
        metadata.put("preHavingGroupCount", preHavingGroupCount);
        metadata.put("havingRemoved", Math.max(0, preHavingGroupCount - fullGroupCount));
        metadata.put("groupCount", output.size());
        metadata.put("fullGroupCount", fullGroupCount);
        metadata.put("outputCount", output.size());
        metadata.put("limited", limited);
        metadata.put("outputLimited", limited);
        metadata.put("outputComplete", !limited);
        metadata.put("authoritativeEmpty", fullGroupCount == 0);

        return new StructuredPipelineResult(true, base.message(), output, null,
                base.completeDataset(), fullGroupCount == 0, base.sourceEntityCount(),
                base.missingValueCount(), metadata);
    }

    private boolean matchesHaving(double actual, StructuredHavingSpec having) {
        List<Double> expected = having.values();
        return switch (having.operator()) {
            case EQ -> Double.compare(actual, expected.get(0)) == 0;
            case NE -> Double.compare(actual, expected.get(0)) != 0;
            case GT -> actual > expected.get(0);
            case GTE -> actual >= expected.get(0);
            case LT -> actual < expected.get(0);
            case LTE -> actual <= expected.get(0);
            case BETWEEN -> actual >= Math.min(expected.get(0), expected.get(1))
                    && actual <= Math.max(expected.get(0), expected.get(1));
            case IN -> expected.stream().anyMatch(value -> Double.compare(actual, value) == 0);
            default -> false;
        };
    }

    private StructuredPipelinePlan copyForHaving(StructuredPipelinePlan plan) {
        return StructuredPipelinePlan.builder()
                .domainCode(plan.getDomainCode())
                .entityType(plan.getEntityType())
                .scope(plan.getScope())
                .select(plan.getSelect())
                .filter(plan.getFilter())
                .groupBy(plan.getGroupBy())
                .aggregate(plan.getAggregate())
                .having(null)
                .orderBy(plan.getOrderBy())
                .distinct(plan.isDistinct())
                .limit(null)
                .build();
    }

    private StructuredPipelineResult executeElementBound(StructuredPipelinePlan plan) {
        // element binding 必须发生在 distinct / final limit 之前，否则先 limit 再删无关元素会漏掉后续真实命中项。
        StructuredPipelinePlan unbounded = copyForElementBinding(plan);
        StructuredPipelineResult base = super.execute(unbounded);
        if (!base.success() || base.scalarValue() != null || base.rows().isEmpty()) return base;

        List<StructuredPipelineResult.Row> bound = bindRows(plan, base.rows());
        if (plan.isDistinct()) bound = distinct(bound, plan.getSelect());

        int fullOutputCount = bound.size();
        boolean limited = plan.getLimit() != null && plan.getLimit() > 0 && bound.size() > plan.getLimit();
        List<StructuredPipelineResult.Row> output = limited
                ? new ArrayList<>(bound.subList(0, plan.getLimit()))
                : bound;

        Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("elementBindingApplied", true);
        metadata.put("elementBindingRemoved", Math.max(0, base.rows().size() - fullOutputCount));
        metadata.put("outputCount", output.size());
        metadata.put("fullOutputCount", fullOutputCount);
        metadata.put("limited", limited);
        metadata.put("outputLimited", limited);
        metadata.put("outputComplete", !limited);
        metadata.put("authoritativeEmpty", fullOutputCount == 0);

        return new StructuredPipelineResult(true, base.message(), output, null,
                base.completeDataset(), fullOutputCount == 0, base.sourceEntityCount(),
                base.missingValueCount(), metadata);
    }

    private StructuredPipelineResult enrichResultContract(StructuredPipelinePlan plan,
                                                          StructuredPipelineResult result) {
        if (result == null) return null;
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        String shape = result.scalarValue() != null ? "SCALAR"
                : plan != null && plan.getGroupBy() != null && !plan.getGroupBy().isEmpty() ? "GROUP" : "ROWS";
        metadata.put("resultShape", shape);
        metadata.put("dataGrain", dataGrain(plan).name());
        if (result.scalarValue() != null) metadata.put("scalarValue", result.scalarValue());
        return new StructuredPipelineResult(result.success(), result.message(), result.rows(), result.scalarValue(),
                result.completeDataset(), result.authoritativeEmpty(), result.sourceEntityCount(),
                result.missingValueCount(), metadata);
    }

    private DataGrain dataGrain(StructuredPipelinePlan plan) {
        if (plan == null) return DataGrain.LOGICAL_ENTITY;
        if (plan.getAggregate() != null && StrUtil.isNotBlank(plan.getAggregate().metricCode())) {
            MetricDefinition metric = metricRegistry.lookup(plan.getDomainCode(), plan.getAggregate().metricCode()).orElse(null);
            if (metric != null && metric.getDataGrain() != null) return metric.getDataGrain();
        }
        if (plan.getOrderBy() != null) {
            for (StructuredOrderSpec order : plan.getOrderBy()) {
                if (order == null || StrUtil.isBlank(order.metricCode())) continue;
                MetricDefinition metric = metricRegistry.lookup(plan.getDomainCode(), order.metricCode()).orElse(null);
                if (metric != null && metric.getDataGrain() != null) return metric.getDataGrain();
            }
        }
        return DataGrain.LOGICAL_ENTITY;
    }

    private boolean requiresElementBinding(StructuredPipelinePlan plan) {
        if (plan == null || plan.getFilter() == null || plan.getAggregate() != null
                || (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty())
                || plan.getSelect() == null || plan.getSelect().isEmpty()) return false;
        for (StructuredValueExpression expression : plan.getSelect()) {
            if (expression != null && expression.explode()
                    && bindingConstraint(plan.getFilter(), expression.fieldCode()) != null) return true;
        }
        return false;
    }

    private StructuredPipelinePlan copyForElementBinding(StructuredPipelinePlan plan) {
        return StructuredPipelinePlan.builder()
                .domainCode(plan.getDomainCode())
                .entityType(plan.getEntityType())
                .scope(plan.getScope())
                .select(plan.getSelect())
                .filter(plan.getFilter())
                .groupBy(plan.getGroupBy())
                .aggregate(plan.getAggregate())
                .having(plan.getHaving())
                .orderBy(plan.getOrderBy())
                .distinct(false)
                .limit(null)
                .build();
    }

    private List<StructuredPipelineResult.Row> bindRows(StructuredPipelinePlan plan,
                                                        List<StructuredPipelineResult.Row> rows) {
        List<StructuredPipelineResult.Row> out = new ArrayList<>();
        for (StructuredPipelineResult.Row row : rows) {
            if (row == null) continue;
            if (!sameSourceExpressionsAligned(plan.getDomainCode(), row, plan.getSelect())) continue;
            if (!matchesGuaranteedElementConstraints(plan.getDomainCode(), row, plan.getSelect(), plan.getFilter())) continue;
            out.add(row);
        }
        return out;
    }

    /**
     * 同一 multi-value source 同时投影“原元素 + 派生元素”时，基础 executor 会形成笛卡尔积。
     * 只要存在原始 explode 表达式，就用它作为 carrier，把其它变换值重新绑定到同一源元素。
     */
    private boolean sameSourceExpressionsAligned(String domainCode,
                                                 StructuredPipelineResult.Row row,
                                                 List<StructuredValueExpression> select) {
        Map<String, List<StructuredValueExpression>> byField = explodedByField(select);
        for (Map.Entry<String, List<StructuredValueExpression>> entry : byField.entrySet()) {
            StructuredValueExpression carrier = rawCarrier(entry.getValue());
            if (carrier == null) continue;
            String raw = row.fields().get(expressionKey(carrier));
            if (raw == null) continue;
            StructuredQueryResult.Row synthetic = syntheticRow(entry.getKey(), raw);
            for (StructuredValueExpression expression : entry.getValue()) {
                if (expression == carrier) continue;
                String projected = row.fields().get(expressionKey(expression));
                if (projected == null) continue;
                List<String> expected = values.values(domainCode, synthetic, expression);
                if (!expected.contains(projected)) return false;
            }
        }
        return true;
    }

    private boolean matchesGuaranteedElementConstraints(String domainCode,
                                                        StructuredPipelineResult.Row row,
                                                        List<StructuredValueExpression> select,
                                                        StructuredPredicateNode filter) {
        Map<String, List<StructuredValueExpression>> byField = explodedByField(select);
        for (Map.Entry<String, List<StructuredValueExpression>> entry : byField.entrySet()) {
            StructuredPredicateNode constraint = bindingConstraint(filter, entry.getKey());
            if (constraint == null) continue;

            StructuredValueExpression carrier = rawCarrier(entry.getValue());
            if (carrier != null) {
                String raw = row.fields().get(expressionKey(carrier));
                if (raw != null && !matchesConstraint(domainCode, syntheticRow(entry.getKey(), raw), constraint)) {
                    return false;
                }
                continue;
            }

            // 没有原始 carrier 时，只能在投影表达式与条件表达式完全一致时安全判断；
            // 不能从一个派生值反推出另一个派生值。
            Boolean projectedMatch = matchesProjectedConstraint(domainCode, row, constraint);
            if (Boolean.FALSE.equals(projectedMatch)) return false;
        }
        return true;
    }

    /**
     * 提取“整棵 filter 成立时必然成立”的指定字段元素约束。
     * AND 可保留其中的同字段条件；OR 只有每个分支都带同字段约束时才可安全绑定。
     */
    private StructuredPredicateNode bindingConstraint(StructuredPredicateNode node, String fieldCode) {
        if (node == null || node.type() == null || fieldCode == null) return null;
        if (node.type() == StructuredPredicateNode.Type.CONDITION) {
            return node.value() != null && node.value().explode()
                    && fieldCode.equalsIgnoreCase(node.value().fieldCode()) ? node : null;
        }
        List<StructuredPredicateNode> children = new ArrayList<>();
        for (StructuredPredicateNode child : node.children()) {
            StructuredPredicateNode bound = bindingConstraint(child, fieldCode);
            if (node.type() == StructuredPredicateNode.Type.OR && bound == null) return null;
            if (bound != null) children.add(bound);
        }
        if (children.isEmpty()) return null;
        if (children.size() == 1) return children.get(0);
        return node.type() == StructuredPredicateNode.Type.AND
                ? StructuredPredicateNode.and(children)
                : StructuredPredicateNode.or(children);
    }

    private boolean matchesConstraint(String domainCode,
                                      StructuredQueryResult.Row row,
                                      StructuredPredicateNode node) {
        return switch (node.type()) {
            case AND -> node.children().stream().allMatch(child -> matchesConstraint(domainCode, row, child));
            case OR -> node.children().stream().anyMatch(child -> matchesConstraint(domainCode, row, child));
            case CONDITION -> {
                List<String> actual = values.values(domainCode, row, node.value());
                String type = values.outputType(domainCode, node.value());
                yield values.matches(node.operator(), actual, node.expected(), type);
            }
        };
    }

    /** true/false=可由投影直接证明；null=缺少原始 carrier，不能安全判断。 */
    private Boolean matchesProjectedConstraint(String domainCode,
                                               StructuredPipelineResult.Row row,
                                               StructuredPredicateNode node) {
        if (node.type() == StructuredPredicateNode.Type.CONDITION) {
            String projected = row.fields().get(expressionKey(node.value()));
            if (projected == null) return null;
            String type = values.outputType(domainCode, node.value());
            return values.matches(node.operator(), List.of(projected), node.expected(), type);
        }
        boolean unknown = false;
        if (node.type() == StructuredPredicateNode.Type.AND) {
            for (StructuredPredicateNode child : node.children()) {
                Boolean matched = matchesProjectedConstraint(domainCode, row, child);
                if (Boolean.FALSE.equals(matched)) return false;
                if (matched == null) unknown = true;
            }
            return unknown ? null : true;
        }
        for (StructuredPredicateNode child : node.children()) {
            Boolean matched = matchesProjectedConstraint(domainCode, row, child);
            if (Boolean.TRUE.equals(matched)) return true;
            if (matched == null) unknown = true;
        }
        return unknown ? null : false;
    }

    private Map<String, List<StructuredValueExpression>> explodedByField(List<StructuredValueExpression> select) {
        Map<String, List<StructuredValueExpression>> out = new LinkedHashMap<>();
        if (select == null) return out;
        for (StructuredValueExpression expression : select) {
            if (expression == null || !expression.explode() || expression.fieldCode() == null) continue;
            out.computeIfAbsent(expression.fieldCode(), ignored -> new ArrayList<>()).add(expression);
        }
        return out;
    }

    private StructuredValueExpression rawCarrier(List<StructuredValueExpression> expressions) {
        if (expressions == null) return null;
        for (StructuredValueExpression expression : expressions) {
            if (expression != null && expression.explode()
                    && (expression.transforms() == null || expression.transforms().isEmpty())) return expression;
        }
        return null;
    }

    private StructuredQueryResult.Row syntheticRow(String fieldCode, String rawElement) {
        return StructuredQueryResult.Row.builder()
                .fields(Map.of(fieldCode, rawElement))
                .build();
    }

    private List<StructuredPipelineResult.Row> distinct(List<StructuredPipelineResult.Row> rows,
                                                        List<StructuredValueExpression> select) {
        Map<String, StructuredPipelineResult.Row> unique = new LinkedHashMap<>();
        for (StructuredPipelineResult.Row row : rows) {
            List<String> tuple = new ArrayList<>();
            for (StructuredValueExpression expression : select) {
                tuple.add(String.valueOf(row.fields().get(expressionKey(expression))));
            }
            unique.putIfAbsent(String.join("\u001f", tuple), row);
        }
        return new ArrayList<>(unique.values());
    }

    private String expressionKey(StructuredValueExpression expression) {
        StringBuilder sb = new StringBuilder(expression.fieldCode());
        if (expression.explode()) sb.append("|EXPLODE");
        for (StructuredValueTransform transform : expression.transforms()) sb.append('|').append(transform.name());
        return sb.toString();
    }
}
