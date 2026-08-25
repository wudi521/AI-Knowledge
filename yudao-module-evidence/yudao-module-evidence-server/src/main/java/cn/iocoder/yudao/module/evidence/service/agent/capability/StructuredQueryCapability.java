package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.EntityDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.SortDirection;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAnswerRenderer;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Domain Registry 驱动的通用结构化能力。
 *
 * <p>Spring 运行时优先进入组合式 StructuredPipeline；旧 StructuredQueryExecutor 只保留给迁移期
 * 非 Spring 单测/兼容路径，避免一次替换破坏 V3 对照测试。</p>
 */
@Component
public class StructuredQueryCapability implements KnowledgeCapability {
    public static final String NAME = "structured_query";

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final DomainEntityRegistry entityRegistry;
    private final StructuredQueryExecutor executor;
    private final StructuredAnswerRenderer renderer;

    @Autowired(required = false)
    private StructuredPipelineCapabilityDelegate pipelineDelegate;

    public StructuredQueryCapability(DomainFieldRegistry fieldRegistry,
                                     DomainMetricRegistry metricRegistry,
                                     DomainEntityRegistry entityRegistry,
                                     StructuredQueryExecutor executor,
                                     StructuredAnswerRenderer renderer) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.entityRegistry = entityRegistry;
        this.executor = executor;
        this.renderer = renderer;
    }

    @Override
    public CapabilityDefinition definition() {
        Map<String, String> schema = pipelineDelegate == null ? legacyArgumentSchema() : pipelineDelegate.argumentSchema();
        return new CapabilityDefinition(NAME, "2",
                "对当前知识库完整结构化数据执行可组合的数据管道：字段读取/安全变换、AND/OR 过滤、多值展开、去重、分组、聚合、排序与 limit。只能使用 Domain Schema 已声明能力。",
                schema, Set.of(), "STRUCTURED_RESULT", true,
                Set.of(), Set.of(), Set.of(), 8_000L, 50);
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (pipelineDelegate != null) return pipelineDelegate.execute(context, arguments);
        return executeLegacy(context, arguments);
    }

    /** 第一纵切兼容执行；线上 Spring Agent 已不走此路径。 */
    private CapabilityResult executeLegacy(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || StrUtil.isBlank(context.domainCode())) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "structured scope is incomplete");
        }
        Task task = task(arguments.get("task"));
        if (task == null) return invalid("invalid task");

        FieldDefinition filterField = resolveField(context.domainCode(), text(arguments.get("field")));
        FilterExpression filter = null;
        if (StrUtil.isNotBlank(text(arguments.get("field")))) {
            if (filterField == null || !filterField.isFilterable()) return invalid("filter field is not registered/filterable");
            FilterOperator operator = FilterOperator.fromExternal(text(arguments.get("operator"))).orElse(FilterOperator.EQ);
            if (filterField.getAllowedOperators() == null || !filterField.getAllowedOperators().contains(operator)) {
                return invalid("operator is not allowed for field " + filterField.getFieldCode());
            }
            List<String> values = strings(arguments.get("values"), 20);
            if (operator != FilterOperator.EXISTS && values.isEmpty()) return invalid("filter values are required");
            filter = FilterExpression.condition(filterField.getFieldCode(), operator, values);
        }

        List<FieldDefinition> projections = resolveFields(context.domainCode(), arguments.get("projections"));
        MetricDefinition metric;
        QueryType queryType;
        Operation operation;
        SortDirection sort = sort(arguments.get("sort"));
        int limit = intValue(arguments.get("limit"), 20, 1, 50);

        if (task == Task.PROJECT || task == Task.LIST) {
            if (projections.isEmpty() && filterField != null) projections = List.of(filterField);
            if (projections.isEmpty()) return invalid("PROJECT/LIST requires projections or a filter field");
            FieldDefinition anchor = projections.get(0);
            metric = syntheticMetric(anchor, context.domainCode());
            metricRegistry.register(metric);
            queryType = QueryType.LIST;
            operation = Operation.NONE;
        } else {
            metric = resolveMetric(context.domainCode(), text(arguments.get("metric")));
            if (metric == null) return invalid(task + " requires a registered metric");
            operation = task == Task.COUNT ? operation(arguments.get("operation"), Operation.COUNT)
                    : task == Task.TOP_N ? Operation.NONE : operation(arguments.get("operation"), null);
            if (operation == null) return invalid("invalid or missing aggregate operation");
            if (operation != Operation.NONE && (metric.getSupportedOperations() == null
                    || !metric.getSupportedOperations().contains(operation))) {
                return invalid("operation " + operation + " is not supported by metric " + metric.getMetricCode());
            }
            queryType = task == Task.TOP_N ? QueryType.TOP_N : QueryType.AGGREGATE;
        }

        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("AGENT_CAPABILITY_LEGACY")
                .queryType(queryType)
                .domainCode(context.domainCode())
                .entityType(metric.getEntityType())
                .scope(QueryScope.currentKb(context.kbId()))
                .metricCode(metric.getMetricCode())
                .fieldCode(projections.isEmpty() ? null : projections.get(0).getFieldCode())
                .projections(projections.stream().map(FieldDefinition::getFieldCode).toList())
                .operation(operation)
                .filters(Map.of("publishedOnly", "true"))
                .filterExpression(filter)
                .sort(sort)
                .limit(task == Task.TOP_N ? limit : null)
                .build();

        StructuredQueryResult result = executor.execute(plan);
        if (result == null || result.isUnsupported() || result.isTruncated()) {
            String reason = result == null ? "structured source returned no result"
                    : StrUtil.blankToDefault(result.getUnsupportedReason(), "structured source is incomplete");
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE, reason);
        }
        List<StructuredQueryResult.Row> rows = result.getRows() == null ? List.of() : result.getRows();
        String answer = render(task, plan, metric, projections, rows, result, limit, context.domainCode());
        List<Long> entityIds = rows.stream().map(StructuredQueryResult.Row::getEntityId)
                .filter(Objects::nonNull).distinct().toList();
        Output output = new Output(task.name(), metric.getMetricCode(), entityIds, rows.size(), result.getValue(), answer,
                summarizeRows(rows, projections, limit));
        int outputCount = task == Task.PROJECT || task == Task.LIST || task == Task.TOP_N
                ? Math.min(rows.size(), limit) : 1;
        return CapabilityResult.success(output, Map.of(
                "outputCount", outputCount,
                "sourceRowCount", rows.size(),
                "entityCount", entityIds.size(),
                "completeDataset", true,
                "task", task.name(),
                "metricCode", metric.getMetricCode()
        ));
    }

    private Map<String, String> legacyArgumentSchema() {
        return Map.ofEntries(
                Map.entry("task", "PROJECT/LIST/COUNT/AGGREGATE/TOP_N。"),
                Map.entry("field", "可选过滤字段 code/别名。"),
                Map.entry("operator", "过滤操作符。"),
                Map.entry("values", "过滤值。"),
                Map.entry("projections", "返回字段数组。"),
                Map.entry("metric", "已注册指标。"),
                Map.entry("operation", "聚合操作。"),
                Map.entry("sort", "ASC/DESC。"),
                Map.entry("limit", "1~50。")
        );
    }

    private String render(Task task, StructuredQueryPlan plan, MetricDefinition metric,
                          List<FieldDefinition> projections, List<StructuredQueryResult.Row> rows,
                          StructuredQueryResult result, int limit, String domainCode) {
        if (task == Task.PROJECT || task == Task.LIST) return renderProjection(rows, projections, limit);
        EntityDefinition entity = entityRegistry.lookup(domainCode, metric.getEntityType()).orElse(null);
        String rendered = renderer.render(plan, metric, entity, result);
        if (StrUtil.isNotBlank(rendered)) return rendered;
        if (task == Task.COUNT || task == Task.AGGREGATE) {
            return metric.getDisplayName() + "=" + (result.getValue() == null ? "0" : formatNumber(result.getValue()));
        }
        return renderProjection(rows, projections, limit);
    }

    private String renderProjection(List<StructuredQueryResult.Row> rows, List<FieldDefinition> projections, int limit) {
        if (rows.isEmpty()) return "当前范围内没有符合条件的已发布对象。";
        StringBuilder sb = new StringBuilder("当前范围共 ").append(rows.size()).append(" 个对象");
        if (rows.size() > limit) sb.append("，展示前 ").append(limit).append(" 个");
        sb.append("：\n");
        int count = 0;
        for (StructuredQueryResult.Row row : rows) {
            if (count++ >= limit) break;
            sb.append(count).append(". ").append(StrUtil.blankToDefault(row.getEntityName(), "对象" + row.getEntityId()));
            Map<String, String> values = row.getFields() == null ? Map.of() : row.getFields();
            List<String> fields = new ArrayList<>();
            for (FieldDefinition projection : projections) {
                fields.add(displayName(projection) + "=" + StrUtil.blankToDefault(values.get(projection.getFieldCode()), "未提供"));
            }
            if (!fields.isEmpty()) sb.append("：").append(String.join("；", fields));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String summarizeRows(List<StructuredQueryResult.Row> rows, List<FieldDefinition> projections, int limit) {
        return rows.stream().limit(Math.min(limit, 12)).map(row -> {
            Map<String, String> values = row.getFields() == null ? Map.of() : row.getFields();
            Map<String, String> selected = new LinkedHashMap<>();
            for (FieldDefinition field : projections) selected.put(field.getFieldCode(), values.get(field.getFieldCode()));
            return "entityId=" + row.getEntityId() + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(row.getEntityName()), 100)
                    + ",fields=" + selected + ",value=" + row.getValue();
        }).collect(Collectors.joining(" | "));
    }

    private FieldDefinition resolveField(String domainCode, String raw) {
        if (StrUtil.isBlank(raw)) return null;
        return fieldRegistry.byCode(domainCode, raw.trim().toUpperCase(Locale.ROOT))
                .or(() -> fieldRegistry.findByAlias(raw.trim(), domainCode)).orElse(null);
    }

    private List<FieldDefinition> resolveFields(String domainCode, Object raw) {
        List<String> names = strings(raw, 12);
        List<FieldDefinition> out = new ArrayList<>();
        for (String name : names) {
            FieldDefinition field = resolveField(domainCode, name);
            if (field != null && out.stream().noneMatch(v -> v.getFieldCode().equals(field.getFieldCode()))) out.add(field);
        }
        return List.copyOf(out);
    }

    private MetricDefinition resolveMetric(String domainCode, String raw) {
        if (StrUtil.isBlank(raw)) return null;
        return metricRegistry.lookup(domainCode, raw.trim().toUpperCase(Locale.ROOT))
                .or(() -> metricRegistry.findByAlias(domainCode, raw.trim())).orElse(null);
    }

    private MetricDefinition syntheticMetric(FieldDefinition field, String domainCode) {
        return MetricDefinition.builder().metricCode(field.getFieldCode()).domainCode(domainCode)
                .entityType(field.getEntityType()).valueType(field.getValueType()).supportedOperations(Set.of())
                .adapterKey(domainCode).aliases(field.getAliases()).displayName(displayName(field)).build();
    }

    private String displayName(FieldDefinition field) {
        if (field.getAliases() != null && !field.getAliases().isEmpty() && StrUtil.isNotBlank(field.getAliases().get(0))) {
            return field.getAliases().get(0);
        }
        return field.getFieldCode();
    }

    private List<String> strings(Object raw, int limit) {
        if (raw == null) return List.of();
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String value = text(item);
                if (StrUtil.isNotBlank(value) && !out.contains(value)) out.add(value);
                if (out.size() >= limit) break;
            }
            return List.copyOf(out);
        }
        String value = text(raw);
        return StrUtil.isBlank(value) ? List.of() : List.of(value);
    }

    private Task task(Object raw) {
        try { return Task.valueOf(text(raw).toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }

    private Operation operation(Object raw, Operation def) {
        if (raw == null || StrUtil.isBlank(text(raw))) return def;
        try { return Operation.valueOf(text(raw).toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }

    private SortDirection sort(Object raw) {
        try { return SortDirection.valueOf(text(raw).toUpperCase(Locale.ROOT)); } catch (Exception e) { return SortDirection.DESC; }
    }

    private int intValue(Object raw, int def, int min, int max) {
        int value = def;
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

    private CapabilityResult invalid(String message) {
        return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, message);
    }

    private enum Task { PROJECT, LIST, COUNT, AGGREGATE, TOP_N }

    public record Output(String task, String metricCode, List<Long> entityIds, int sourceRowCount,
                         Double value, String answer, String rowSummary) implements AgentCapabilityOutput {
        @Override
        public String summary() {
            return "task=" + task + "; metric=" + metricCode + "; entityIds=" + entityIds
                    + "; sourceRowCount=" + sourceRowCount + "; value=" + value + "; rows=" + rowSummary;
        }

        @Override
        public String progressHash() {
            return task + ":" + metricCode + ":" + entityIds + ":" + value + ":" + rowSummary.hashCode();
        }

        @Override
        public List<Long> verifiedEntityIds() {
            return entityIds == null ? List.of() : List.copyOf(entityIds);
        }

        @Override
        public String deterministicAnswer() { return answer; }
    }
}
