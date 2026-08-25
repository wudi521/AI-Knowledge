package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.SortDirection;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAggregateSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredOrderSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPredicateNode;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把 Planner 的受控 JSON 编译为 StructuredPipelinePlan。
 * 兼容 V1.1 第一版 task/field/metric 参数，但新 Planner 应优先使用组合式参数。
 */
@Component
public class StructuredPipelineCapabilityDelegate {
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final DomainEntityRegistry entityRegistry;
    private final StructuredPipelineExecutor executor;

    public StructuredPipelineCapabilityDelegate(DomainFieldRegistry fieldRegistry,
                                                DomainMetricRegistry metricRegistry,
                                                DomainEntityRegistry entityRegistry,
                                                StructuredPipelineExecutor executor) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.entityRegistry = entityRegistry;
        this.executor = executor;
    }

    /**
     * 只编译、不访问数据。用于 Agent 在执行前按“规范化执行计划”做等价调用去重。
     * JSON 字段顺序、默认值省略、等价别名等只要最终编译成同一 Pipeline，就必须得到同一个 key。
     */
    public String canonicalPlanKey(String domainCode, Map<String, Object> arguments) {
        if (StrUtil.isBlank(domainCode)) return null;
        CompileResult compiled = compile(domainCode, arguments == null ? Map.of() : arguments);
        return compiled.success() ? summarizePlan(compiled.plan()) : null;
    }

    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || StrUtil.isBlank(context.domainCode())) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "structured scope is incomplete");
        }
        CompileResult compiled = compile(context.domainCode(), arguments == null ? Map.of() : arguments);
        if (!compiled.success()) {
            return CapabilityResult.recoverableFailure(compiled.message(), Map.of(
                    "errorKind", "PLAN_CONTRACT",
                    "domainCode", context.domainCode()));
        }
        StructuredPipelinePlan plan = compiled.plan();
        plan.setScope(QueryScope.currentKb(context.kbId()));
        StructuredPipelineResult result = executor.execute(plan);
        if (!result.success()) {
            String message = StrUtil.blankToDefault(result.message(), "structured pipeline failed");
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            if (result.metadata() != null) diagnostics.putAll(result.metadata());
            diagnostics.put("normalizedPlan", summarizePlan(plan));
            if (isContractError(message)) {
                diagnostics.put("errorKind", "PIPELINE_CONTRACT");
                return CapabilityResult.recoverableFailure(message, diagnostics);
            }
            diagnostics.putIfAbsent("errorKind", "STRUCTURED_DATA_INCOMPLETE");
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE, message, diagnostics);
        }

        String shape = result.scalarValue() != null ? "SCALAR"
                : !safe(plan.getGroupBy()).isEmpty() ? "GROUP" : "ROWS";
        String answer = render(plan, result, context.domainCode());
        List<Long> verifiedIds = "ROWS".equals(shape)
                ? result.rows().stream().map(StructuredPipelineResult.Row::entityId)
                    .filter(Objects::nonNull).distinct().toList()
                : List.of();
        String rowSummary = summarizeRows(result.rows(), 12);
        Output output = new Output(shape, verifiedIds, result.sourceEntityCount(), result.scalarValue(), answer,
                rowSummary, result.authoritativeEmpty(), summarizePlan(plan));
        int outputCount = "ROWS".equals(shape) || "GROUP".equals(shape) ? result.rows().size() : 1;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.metadata() != null) metadata.putAll(result.metadata());
        metadata.put("outputCount", outputCount);
        metadata.put("sourceRowCount", result.sourceEntityCount());
        metadata.put("entityCount", verifiedIds.size());
        metadata.put("completeDataset", result.completeDataset());
        metadata.putIfAbsent("outputComplete", true);
        metadata.put("authoritativeEmpty", result.authoritativeEmpty());
        metadata.put("missingValueCount", result.missingValueCount());
        metadata.put("task", "SCALAR".equals(shape) || "GROUP".equals(shape) ? "AGGREGATE" : "PROJECT");
        metadata.put("shape", shape);
        metadata.put("normalizedPlan", summarizePlan(plan));
        return CapabilityResult.success(output, metadata);
    }

    public Map<String, String> argumentSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("select", "可选。返回字段/值表达式数组。元素可为字段 code/别名，或 {field,explode,transforms[]}。例如 [{field:'TITLE'},{field:'FILING_DATE'}]");
        schema.put("filter", "可选。类型化过滤树：条件 {field,operator,values,explode,transforms}；组合 {logic:'AND|OR',children:[...]}。");
        schema.put("groupBy", "可选。分组值表达式或数组；字段必须 groupable=true。多值字段可 explode=true；日期可 YEAR/YEAR_MONTH。");
        schema.put("aggregate", "可选。{operation:'COUNT|COUNT_DISTINCT|SUM|AVG|MIN|MAX', field?, metric?, explode?, transforms?}。COUNT 可不带 field/metric。");
        schema.put("orderBy", "可选。排序对象或数组：{field?,metric?,aggregateValue?,explode?,transforms?,direction:'ASC|DESC'}。字段必须 sortable=true。");
        schema.put("distinct", "可选 boolean。对最终 select 值去重。");
        schema.put("limit", "可选 1~50。只限制最终输出；底层仍基于完整集合计算。" );
        schema.put("task", "兼容参数：PROJECT/LIST/COUNT/AGGREGATE/TOP_N。新计划无需 task。" );
        schema.put("field", "兼容单字段过滤/排序字段。" );
        schema.put("operator", "兼容过滤操作符。" );
        schema.put("values", "兼容过滤值。" );
        schema.put("projections", "兼容返回字段数组。" );
        schema.put("metric", "兼容指标；若名称实际解析为 Field，则按字段表达式执行而不是伪造 Metric。" );
        schema.put("operation", "兼容聚合操作。" );
        schema.put("sort", "兼容 ASC/DESC。" );
        schema.put("transforms", "兼容值变换数组，如 YEAR/LENGTH/PERSON_SURNAME。" );
        return schema;
    }

    private CompileResult compile(String domainCode, Map<String, Object> args) {
        try {
            List<StructuredValueExpression> select = expressions(domainCode,
                    firstNonNull(args.get("select"), args.get("projections")), false);
            StructuredPredicateNode filter = predicate(domainCode, args.get("filter"));
            if (filter == null && StrUtil.isNotBlank(text(args.get("field"))) && args.get("task") != null) {
                filter = legacyFilter(domainCode, args);
            }
            List<StructuredValueExpression> groupBy = expressions(domainCode, args.get("groupBy"), true);
            StructuredAggregateSpec aggregate = aggregate(domainCode, args.get("aggregate"));
            List<StructuredOrderSpec> orderBy = orders(domainCode, args.get("orderBy"));
            int limit = intValue(args.get("limit"), 20, 1, 50);
            boolean distinct = bool(args.get("distinct"));

            String legacyTask = text(args.get("task")).toUpperCase(Locale.ROOT);
            if (aggregate == null && ("COUNT".equals(legacyTask) || "AGGREGATE".equals(legacyTask))) {
                aggregate = legacyAggregate(domainCode, args, legacyTask);
            }
            if (orderBy.isEmpty() && "TOP_N".equals(legacyTask)) {
                StructuredOrderSpec legacyOrder = legacyOrder(domainCode, args, select);
                if (legacyOrder == null) return CompileResult.failure("TOP_N requires an executable sortable field or metric");
                orderBy = List.of(legacyOrder);
            }
            if (select.isEmpty() && ("PROJECT".equals(legacyTask) || "LIST".equals(legacyTask))) {
                String rawField = text(args.get("field"));
                if (StrUtil.isNotBlank(rawField)) select = List.of(expression(domainCode, rawField, false, transforms(args.get("transforms"))));
            }
            if (select.isEmpty() && aggregate == null && groupBy.isEmpty() && !orderBy.isEmpty()
                    && orderBy.get(0).value() != null) {
                select = List.of(orderBy.get(0).value());
            }

            String entityType = entityType(domainCode, select, filter, groupBy, aggregate, orderBy);
            if (StrUtil.isBlank(entityType)) return CompileResult.failure("cannot resolve structured entity type from fields/metrics");
            if (select.isEmpty() && aggregate == null && groupBy.isEmpty() && orderBy.isEmpty() && filter == null) {
                return CompileResult.failure("structured query has no operation");
            }
            StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                    .domainCode(domainCode)
                    .entityType(entityType)
                    .select(select)
                    .filter(filter)
                    .groupBy(groupBy)
                    .aggregate(aggregate)
                    .orderBy(orderBy)
                    .distinct(distinct)
                    .limit(aggregate != null && groupBy.isEmpty() ? null : limit)
                    .build();
            return CompileResult.success(plan);
        } catch (IllegalArgumentException e) {
            return CompileResult.failure(e.getMessage());
        }
    }

    private StructuredAggregateSpec legacyAggregate(String domainCode, Map<String, Object> args, String task) {
        Operation op = operation(args.get("operation"), "COUNT".equals(task) ? Operation.COUNT : null);
        if (op == null) throw new IllegalArgumentException("aggregate operation is invalid or missing");
        String raw = text(args.get("metric"));
        if (StrUtil.isBlank(raw)) {
            String fieldRaw = text(args.get("field"));
            if (StrUtil.isBlank(fieldRaw)) return new StructuredAggregateSpec(op, null, null);
            return new StructuredAggregateSpec(op,
                    expression(domainCode, fieldRaw, true, transforms(args.get("transforms"))), null);
        }
        MetricDefinition metric = resolveMetric(domainCode, raw);
        if (metric != null) return new StructuredAggregateSpec(op, null, metric.getMetricCode());
        FieldDefinition field = resolveField(domainCode, raw);
        if (field != null) return new StructuredAggregateSpec(op,
                expression(domainCode, field.getFieldCode(), true, transforms(args.get("transforms"))), null);
        throw new IllegalArgumentException("metric/field is not registered: " + raw);
    }

    private StructuredOrderSpec legacyOrder(String domainCode, Map<String, Object> args,
                                            List<StructuredValueExpression> select) {
        SortDirection direction = direction(firstNonNull(args.get("sort"), "DESC"));
        String rawMetric = text(args.get("metric"));
        if (StrUtil.isNotBlank(rawMetric)) {
            MetricDefinition metric = resolveMetric(domainCode, rawMetric);
            if (metric != null) return new StructuredOrderSpec(null, metric.getMetricCode(), false, direction);
            FieldDefinition field = resolveField(domainCode, rawMetric);
            if (field != null) return new StructuredOrderSpec(
                    expression(domainCode, field.getFieldCode(), false, transforms(args.get("transforms"))), null, false, direction);
        }
        String rawField = text(args.get("field"));
        if (StrUtil.isNotBlank(rawField)) return new StructuredOrderSpec(
                expression(domainCode, rawField, false, transforms(args.get("transforms"))), null, false, direction);
        if (!select.isEmpty()) return new StructuredOrderSpec(select.get(0), null, false, direction);
        return null;
    }

    private StructuredAggregateSpec aggregate(String domainCode, Object raw) {
        Map<String, Object> map = map(raw);
        if (map.isEmpty()) return null;
        Operation op = operation(map.get("operation"), null);
        if (op == null) throw new IllegalArgumentException("aggregate.operation is required");
        String metricRaw = text(map.get("metric"));
        if (StrUtil.isNotBlank(metricRaw)) {
            MetricDefinition metric = resolveMetric(domainCode, metricRaw);
            if (metric != null) return new StructuredAggregateSpec(op, null, metric.getMetricCode());
            FieldDefinition field = resolveField(domainCode, metricRaw);
            if (field != null) return new StructuredAggregateSpec(op,
                    expression(domainCode, field.getFieldCode(), boolDefault(map.get("explode"), field.isMultiValue()),
                            transforms(map.get("transforms"))), null);
            throw new IllegalArgumentException("aggregate metric/field is not registered: " + metricRaw);
        }
        Object valueRaw = firstNonNull(map.get("value"), map.get("field"));
        if (valueRaw == null) return new StructuredAggregateSpec(op, null, null);
        StructuredValueExpression value = valueRaw instanceof Map<?, ?> || valueRaw instanceof JSONObject
                ? valueExpression(domainCode, valueRaw, true)
                : expression(domainCode, text(valueRaw), true, transforms(map.get("transforms")));
        return new StructuredAggregateSpec(op, value, null);
    }

    private List<StructuredOrderSpec> orders(String domainCode, Object raw) {
        if (raw == null) return List.of();
        List<Object> items = objectList(raw);
        List<StructuredOrderSpec> out = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = map(item);
            if (map.isEmpty()) continue;
            SortDirection direction = direction(firstNonNull(map.get("direction"), map.get("sort")));
            if (bool(map.get("aggregateValue"))) {
                out.add(new StructuredOrderSpec(null, null, true, direction));
                continue;
            }
            String metricRaw = text(map.get("metric"));
            if (StrUtil.isNotBlank(metricRaw)) {
                MetricDefinition metric = resolveMetric(domainCode, metricRaw);
                if (metric == null) throw new IllegalArgumentException("order metric is not registered: " + metricRaw);
                out.add(new StructuredOrderSpec(null, metric.getMetricCode(), false, direction));
                continue;
            }
            StructuredValueExpression value = valueExpression(domainCode, firstNonNull(map.get("value"), map), false);
            out.add(new StructuredOrderSpec(value, null, false, direction));
        }
        return List.copyOf(out);
    }

    private StructuredPredicateNode predicate(String domainCode, Object raw) {
        Map<String, Object> map = map(raw);
        if (map.isEmpty()) return null;
        String logic = text(map.get("logic")).toUpperCase(Locale.ROOT);
        if ("AND".equals(logic) || "OR".equals(logic)) {
            List<StructuredPredicateNode> children = new ArrayList<>();
            for (Object child : objectList(firstNonNull(map.get("children"), map.get("conditions")))) {
                StructuredPredicateNode parsed = predicate(domainCode, child);
                if (parsed != null) children.add(parsed);
            }
            if (children.isEmpty()) throw new IllegalArgumentException("filter boolean group has no children");
            return "AND".equals(logic) ? StructuredPredicateNode.and(children) : StructuredPredicateNode.or(children);
        }
        StructuredValueExpression value = valueExpression(domainCode, map, true);
        FilterOperator operator = FilterOperator.fromExternal(text(map.get("operator"))).orElse(FilterOperator.EQ);
        return StructuredPredicateNode.condition(value, operator, strings(map.get("values"), 50));
    }

    private StructuredPredicateNode legacyFilter(String domainCode, Map<String, Object> args) {
        String rawField = text(args.get("field"));
        if (StrUtil.isBlank(rawField)) return null;
        StructuredValueExpression value = expression(domainCode, rawField, true, transforms(args.get("transforms")));
        FilterOperator operator = FilterOperator.fromExternal(text(args.get("operator"))).orElse(FilterOperator.EQ);
        return StructuredPredicateNode.condition(value, operator, strings(args.get("values"), 50));
    }

    private List<StructuredValueExpression> expressions(String domainCode, Object raw, boolean defaultExplode) {
        if (raw == null) return List.of();
        List<Object> items = objectList(raw);
        List<StructuredValueExpression> out = new ArrayList<>();
        for (Object item : items) {
            StructuredValueExpression expression = valueExpression(domainCode, item, defaultExplode);
            if (expression != null && out.stream().noneMatch(v -> expressionKey(v).equals(expressionKey(expression)))) out.add(expression);
        }
        return List.copyOf(out);
    }

    private StructuredValueExpression valueExpression(String domainCode, Object raw, boolean defaultExplode) {
        if (raw == null) return null;
        if (raw instanceof String || raw instanceof Number) return expression(domainCode, text(raw), defaultExplode, List.of());
        Map<String, Object> map = map(raw);
        String fieldRaw = text(firstNonNull(map.get("field"), map.get("code")));
        if (StrUtil.isBlank(fieldRaw)) return null;
        FieldDefinition field = resolveField(domainCode, fieldRaw);
        if (field == null) throw new IllegalArgumentException("field is not registered: " + fieldRaw);
        boolean explode = boolDefault(map.get("explode"), defaultExplode && field.isMultiValue());
        return new StructuredValueExpression(field.getFieldCode(), explode, transforms(map.get("transforms")));
    }

    private StructuredValueExpression expression(String domainCode, String rawField, boolean defaultExplode,
                                                 List<StructuredValueTransform> transforms) {
        FieldDefinition field = resolveField(domainCode, rawField);
        if (field == null) throw new IllegalArgumentException("field is not registered: " + rawField);
        return new StructuredValueExpression(field.getFieldCode(), defaultExplode && field.isMultiValue(), transforms);
    }

    private String entityType(String domainCode,
                              List<StructuredValueExpression> select,
                              StructuredPredicateNode filter,
                              List<StructuredValueExpression> groupBy,
                              StructuredAggregateSpec aggregate,
                              List<StructuredOrderSpec> orderBy) {
        List<String> candidates = new ArrayList<>();
        for (StructuredValueExpression e : select) addEntityType(domainCode, e, candidates);
        collectFilterEntityTypes(domainCode, filter, candidates);
        for (StructuredValueExpression e : groupBy) addEntityType(domainCode, e, candidates);
        if (aggregate != null) {
            addEntityType(domainCode, aggregate.value(), candidates);
            if (StrUtil.isNotBlank(aggregate.metricCode())) {
                MetricDefinition m = metricRegistry.lookup(domainCode, aggregate.metricCode()).orElse(null);
                if (m != null) candidates.add(m.getEntityType());
            }
        }
        for (StructuredOrderSpec order : orderBy) {
            addEntityType(domainCode, order.value(), candidates);
            if (StrUtil.isNotBlank(order.metricCode())) {
                MetricDefinition m = metricRegistry.lookup(domainCode, order.metricCode()).orElse(null);
                if (m != null) candidates.add(m.getEntityType());
            }
        }
        List<String> unique = candidates.stream().filter(StrUtil::isNotBlank).distinct().toList();
        if (unique.size() > 1) throw new IllegalArgumentException("cross-entity structured pipeline is not allowed: " + unique);
        return unique.isEmpty() ? null : unique.get(0);
    }

    private void addEntityType(String domainCode, StructuredValueExpression value, List<String> out) {
        if (value == null) return;
        FieldDefinition field = fieldRegistry.byCode(domainCode, value.fieldCode()).orElse(null);
        if (field != null) out.add(field.getEntityType());
    }

    private void collectFilterEntityTypes(String domainCode, StructuredPredicateNode node, List<String> out) {
        if (node == null) return;
        addEntityType(domainCode, node.value(), out);
        for (StructuredPredicateNode child : node.children()) collectFilterEntityTypes(domainCode, child, out);
    }

    private String render(StructuredPipelinePlan plan, StructuredPipelineResult result, String domainCode) {
        String filter = predicateLabel(domainCode, plan.getFilter());
        if (result.authoritativeEmpty()) {
            return StrUtil.isBlank(filter)
                    ? "当前完整结构化范围内没有符合条件的已发布对象。"
                    : "筛选条件【" + filter + "】未命中任何已发布对象。";
        }
        String prefix = StrUtil.isBlank(filter) ? "" : "筛选条件【" + filter + "】已命中。";
        if (result.scalarValue() != null && plan.getAggregate() != null) {
            StructuredAggregateSpec aggregate = plan.getAggregate();
            String label = aggregateLabel(domainCode, aggregate);
            String number = formatNumber(result.scalarValue());
            String body;
            if (aggregate.operation() == Operation.COUNT_DISTINCT) body = "当前范围内不同" + label + "共有 " + number + " 个。";
            else if (aggregate.operation() == Operation.COUNT) body = "当前范围内" + label + "共有 " + number + " 个。";
            else body = label + "的" + aggregate.operation().name() + "结果为 " + number + "。";
            return prefix + body;
        }
        if (!safe(plan.getGroupBy()).isEmpty()) {
            StringBuilder sb = new StringBuilder(prefix).append("分组结果：\n");
            int i = 1;
            for (StructuredPipelineResult.Row row : result.rows()) {
                sb.append(i++).append(". ").append(row.groupKey()).append("：")
                        .append(formatNumber(row.value())).append('\n');
            }
            return sb.toString().trim();
        }
        StringBuilder sb = new StringBuilder(prefix)
                .append("当前范围返回 ").append(result.rows().size()).append(" 个结果：\n");
        int i = 1;
        for (StructuredPipelineResult.Row row : result.rows()) {
            sb.append(i++).append(". ").append(StrUtil.blankToDefault(row.entityName(), "对象" + row.entityId()));
            if (!row.fields().isEmpty()) {
                List<String> pairs = new ArrayList<>();
                row.fields().forEach((k, v) -> pairs.add(displayExpression(domainCode, k) + "=" + StrUtil.blankToDefault(v, "未提供")));
                sb.append("：").append(String.join("；", pairs));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String predicateLabel(String domainCode, StructuredPredicateNode node) {
        if (node == null || node.type() == null) return "";
        if (node.type() == StructuredPredicateNode.Type.AND || node.type() == StructuredPredicateNode.Type.OR) {
            String joiner = node.type() == StructuredPredicateNode.Type.AND ? " 且 " : " 或 ";
            return node.children().stream().map(child -> predicateLabel(domainCode, child))
                    .filter(StrUtil::isNotBlank).collect(Collectors.joining(joiner));
        }
        if (node.value() == null || node.operator() == null) return "";
        String label = expressionLabel(domainCode, node.value());
        String op = operatorLabel(node.operator());
        if (node.operator() == FilterOperator.EXISTS) return label + "有值";
        return label + op + String.join("、", node.expected());
    }

    private String operatorLabel(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "≠";
            case IN -> "属于";
            case CONTAINS -> "包含";
            case STARTS_WITH -> "以…开头:";
            case GT -> ">";
            case GTE -> "≥";
            case LT -> "<";
            case LTE -> "≤";
            case BETWEEN -> "介于";
            case EXISTS -> "";
        };
    }

    private String aggregateLabel(String domainCode, StructuredAggregateSpec aggregate) {
        if (aggregate.value() != null) return expressionLabel(domainCode, aggregate.value());
        if (StrUtil.isNotBlank(aggregate.metricCode())) {
            MetricDefinition metric = metricRegistry.lookup(domainCode, aggregate.metricCode()).orElse(null);
            return metric == null ? aggregate.metricCode() : StrUtil.blankToDefault(metric.getDisplayName(), aggregate.metricCode());
        }
        return entityRegistry.all(domainCode).stream().findFirst().map(EntityDefinition::getDisplayLabel).orElse("对象");
    }

    private String expressionLabel(String domainCode, StructuredValueExpression expression) {
        FieldDefinition field = fieldRegistry.byCode(domainCode, expression.fieldCode()).orElse(null);
        String base = field == null ? expression.fieldCode() : displayName(field);
        for (StructuredValueTransform transform : expression.transforms()) {
            base = switch (transform) {
                case LENGTH -> base + "长度";
                case YEAR -> base + "年份";
                case MONTH -> base + "月份";
                case YEAR_MONTH -> base + "年月";
                case VALUE_COUNT -> base + "数量";
                case PERSON_SURNAME -> base + "姓氏";
            };
        }
        return base;
    }

    private String displayExpression(String domainCode, String key) {
        String[] parts = key.split("\\|");
        FieldDefinition field = fieldRegistry.byCode(domainCode, parts[0]).orElse(null);
        String label = field == null ? parts[0] : displayName(field);
        for (int i = 1; i < parts.length; i++) {
            if ("EXPLODE".equals(parts[i])) continue;
            try { label = expressionLabel(domainCode, new StructuredValueExpression(parts[0], false,
                    List.of(StructuredValueTransform.valueOf(parts[i])))); }
            catch (Exception ignore) { }
        }
        return label;
    }

    private String summarizeRows(List<StructuredPipelineResult.Row> rows, int limit) {
        return rows.stream().limit(limit).map(r -> "entityId=" + r.entityId() + ",name="
                + StrUtil.maxLength(StrUtil.nullToEmpty(r.entityName()), 100) + ",group=" + r.groupKey()
                + ",fields=" + r.fields() + ",value=" + r.value()).collect(Collectors.joining(" | "));
    }

    private String summarizePlan(StructuredPipelinePlan plan) {
        return "select=" + safe(plan.getSelect()) + "; filter=" + plan.getFilter() + "; groupBy=" + safe(plan.getGroupBy())
                + "; aggregate=" + plan.getAggregate() + "; orderBy=" + safe(plan.getOrderBy())
                + "; distinct=" + plan.isDistinct() + "; limit=" + plan.getLimit();
    }

    private boolean isContractError(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("not registered") || lower.contains("not sortable") || lower.contains("not groupable")
                || lower.contains("not allowed") || lower.contains("requires") || lower.contains("invalid")
                || lower.contains("order-by source") || lower.contains("does not accept") || lower.contains("compatible");
    }

    private FieldDefinition resolveField(String domainCode, String raw) {
        if (StrUtil.isBlank(raw)) return null;
        String normalized = raw.trim();
        return fieldRegistry.byCode(domainCode, normalized.toUpperCase(Locale.ROOT))
                .or(() -> fieldRegistry.findByAlias(normalized, domainCode)).orElse(null);
    }

    private MetricDefinition resolveMetric(String domainCode, String raw) {
        if (StrUtil.isBlank(raw)) return null;
        String normalized = raw.trim();
        return metricRegistry.lookup(domainCode, normalized.toUpperCase(Locale.ROOT))
                .or(() -> metricRegistry.findByAlias(domainCode, normalized)).orElse(null);
    }

    private List<StructuredValueTransform> transforms(Object raw) {
        List<String> names = strings(raw, 8);
        List<StructuredValueTransform> out = new ArrayList<>();
        for (String name : names) {
            try { out.add(StructuredValueTransform.valueOf(name.trim().toUpperCase(Locale.ROOT))); }
            catch (Exception e) { throw new IllegalArgumentException("unknown transform: " + name); }
        }
        return List.copyOf(out);
    }

    private Operation operation(Object raw, Operation fallback) {
        if (raw == null || StrUtil.isBlank(text(raw))) return fallback;
        try { return Operation.valueOf(text(raw).toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    private SortDirection direction(Object raw) {
        try { return SortDirection.valueOf(text(raw).toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return SortDirection.DESC; }
    }

    private List<String> strings(Object raw, int limit) {
        if (raw == null) return List.of();
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String v = text(item);
                if (StrUtil.isNotBlank(v) && !out.contains(v)) out.add(v);
                if (out.size() >= limit) break;
            }
            return List.copyOf(out);
        }
        String value = text(raw);
        return StrUtil.isBlank(value) ? List.of() : List.of(value);
    }

    private List<Object> objectList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) out.add(item);
            return List.copyOf(out);
        }
        return List.of(raw);
    }

    private Map<String, Object> map(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof JSONObject json) {
            Map<String, Object> out = new LinkedHashMap<>();
            json.forEach(out::put);
            return out;
        }
        if (raw instanceof Map<?, ?> source) {
            Map<String, Object> out = new LinkedHashMap<>();
            source.forEach((k, v) -> { if (k != null) out.put(String.valueOf(k), v); });
            return out;
        }
        return Map.of();
    }

    private Object firstNonNull(Object a, Object b) { return a != null ? a : b; }
    private boolean bool(Object raw) { return raw instanceof Boolean b ? b : raw != null && Boolean.parseBoolean(String.valueOf(raw)); }
    private boolean boolDefault(Object raw, boolean fallback) { return raw == null ? fallback : bool(raw); }
    private int intValue(Object raw, int fallback, int min, int max) {
        int value = fallback;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        return Math.max(min, Math.min(max, value));
    }
    private String text(Object raw) { return raw == null ? "" : String.valueOf(raw).trim(); }
    private String formatNumber(Double value) {
        if (value == null) return "0";
        if (value == Math.floor(value) && !Double.isInfinite(value)) return String.valueOf(value.longValue());
        return String.format(Locale.ROOT, "%.2f", value);
    }
    private <T> List<T> safe(Collection<T> source) { return source == null ? List.of() : List.copyOf(source); }
    private String expressionKey(StructuredValueExpression expression) {
        StringBuilder sb = new StringBuilder(expression.fieldCode());
        if (expression.explode()) sb.append("|EXPLODE");
        for (StructuredValueTransform transform : expression.transforms()) sb.append('|').append(transform.name());
        return sb.toString();
    }
    private String displayName(FieldDefinition field) {
        if (field.getAliases() != null && !field.getAliases().isEmpty() && StrUtil.isNotBlank(field.getAliases().get(0))) return field.getAliases().get(0);
        return field.getFieldCode();
    }

    private record CompileResult(boolean success, StructuredPipelinePlan plan, String message) {
        static CompileResult success(StructuredPipelinePlan plan) { return new CompileResult(true, plan, null); }
        static CompileResult failure(String message) { return new CompileResult(false, null, message); }
    }

    public record Output(String shape,
                         List<Long> entityIds,
                         int sourceRowCount,
                         Double value,
                         String answer,
                         String rowSummary,
                         boolean authoritativeEmpty,
                         String normalizedPlan) implements AgentCapabilityOutput {
        @Override public String summary() {
            return "shape=" + shape + "; entityIds=" + entityIds + "; sourceRowCount=" + sourceRowCount
                    + "; value=" + value + "; authoritativeEmpty=" + authoritativeEmpty
                    + "; plan=" + normalizedPlan + "; rows=" + rowSummary;
        }
        @Override public String progressHash() {
            return shape + ":" + entityIds + ":" + value + ":" + authoritativeEmpty + ":" + normalizedPlan.hashCode() + ":" + rowSummary.hashCode();
        }
        @Override public List<Long> verifiedEntityIds() { return entityIds == null ? List.of() : List.copyOf(entityIds); }
        @Override public String deterministicAnswer() { return answer; }
    }
}
