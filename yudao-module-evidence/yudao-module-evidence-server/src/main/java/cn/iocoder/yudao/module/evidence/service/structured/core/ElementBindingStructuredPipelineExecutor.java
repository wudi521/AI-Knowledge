package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多值字段的元素绑定执行层。
 *
 * <p>StructuredPipelineExecutor 的过滤语义是实体级 existential match：一条实体只要任一多值元素命中，
 * 实体就会进入后续投影。这对“哪些专利包含某发明人”是正确的，但对“把命中的发明人列出来”还不够，
 * 因为后续 explode 会再次展开该实体的全部元素。</p>
 *
 * <p>本执行器保持实体级过滤语义不变，只在最终 ROW projection 上增加通用 element binding：
 * 当 filter 与 select 基于同一个 explode 多值源字段时，最终只保留真正满足该字段条件的源元素；
 * 同一源字段的多个投影表达式也按同一个原始元素对齐，避免笛卡尔积把无关元素重新带回结果。</p>
 *
 * <p>这是结构化运算原语，不包含“姓氏/专利”等业务问题分支。</p>
 */
@Primary
@Component
public class ElementBindingStructuredPipelineExecutor extends StructuredPipelineExecutor {

    private final DomainFieldRegistry fieldRegistry;
    private final StructuredValueEvaluator values;

    public ElementBindingStructuredPipelineExecutor(DomainFieldRegistry fieldRegistry,
                                                    DomainMetricRegistry metricRegistry,
                                                    List<DomainStructuredDataAdapter> adapters,
                                                    StructuredValueEvaluator values) {
        super(fieldRegistry, metricRegistry, adapters, values);
        this.fieldRegistry = fieldRegistry;
        this.values = values;
    }

    @Override
    public StructuredPipelineResult execute(StructuredPipelinePlan plan) {
        if (!requiresElementBinding(plan)) return super.execute(plan);

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
            if (expression == null || !expression.explode()) continue;
            FieldDefinition field = fieldRegistry.byCode(null, expression.fieldCode()).orElse(null);
            // 某些 registry 要求 domainCode；这里只按 plan 已验证后的 expression 分组，field lookup 仅是防御性检查。
            if (field == null && expression.fieldCode() == null) continue;
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
