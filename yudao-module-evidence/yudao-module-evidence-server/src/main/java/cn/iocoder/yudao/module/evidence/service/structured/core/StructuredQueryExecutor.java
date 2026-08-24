package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structured Query Executor(Platform Core 领域无关)。 */
@Slf4j
@Component
public class StructuredQueryExecutor {

    private final DomainMetricRegistry metricRegistry;
    private final java.util.List<DomainStructuredDataAdapter> adapters;

    public StructuredQueryExecutor(DomainMetricRegistry metricRegistry,
                                   java.util.List<DomainStructuredDataAdapter> adapters) {
        this.metricRegistry = metricRegistry;
        this.adapters = adapters;
    }

    public StructuredQueryResult execute(StructuredQueryPlan plan) {
        if (plan == null || plan.getMetricCode() == null) return StructuredQueryResult.unsupported("指标未解析");
        MetricDefinition metric = metricRegistry.lookup(plan.getDomainCode(), plan.getMetricCode()).orElse(null);
        if (metric == null) return StructuredQueryResult.unsupported("指标未注册: " + plan.getMetricCode());
        Operation op = plan.getOperation() == null ? Operation.NONE : plan.getOperation();
        if (op != Operation.NONE && !metric.getSupportedOperations().contains(op)) {
            return StructuredQueryResult.unsupported("运算不支持: " + op + " 不在 " + metric.getMetricCode() + " 支持范围");
        }
        DomainStructuredDataAdapter adapter = adapters.stream()
                .filter(a -> a.adapterKey().equals(metric.getAdapterKey())).findFirst().orElse(null);
        if (adapter == null) return StructuredQueryResult.unsupported("数据适配器缺失: " + metric.getAdapterKey());

        StructuredQueryResult data = adapter.execute(plan);
        if (data == null || data.isUnsupported()) {
            return data == null ? StructuredQueryResult.unsupported("数据源未返回") : data;
        }

        // Filter Tree 必须建立在完整 rows 上；源已截断时禁止“先截断再过滤”导致漏匹配。
        if (plan.getFilterExpression() != null) {
            if (data.isTruncated()) {
                return StructuredQueryResult.unsupported("过滤查询的数据源未返回完整数据集, 无法保证过滤结果完整");
            }
            List<StructuredQueryResult.Row> source = data.getRows() == null ? List.of() : data.getRows();
            List<StructuredQueryResult.Row> filtered = source.stream()
                    .filter(row -> StructuredFilterEvaluator.matches(row, plan.getFilterExpression()))
                    .toList();
            data.setRows(filtered);
            data.setRowCount(filtered.size());
            data.setTotalEntities(filtered.size());
        }

        if (data.isTruncated() && requiresCompleteDataset(plan.getQueryType(), op)) {
            return StructuredQueryResult.unsupported("数据源未返回完整数据集, 无法保证全集结论");
        }
        return compute(plan, metric, data);
    }

    public static boolean requiresCompleteDataset(QueryType queryType, Operation operation) {
        if (operation == Operation.COUNT || operation == Operation.COUNT_DISTINCT
                || operation == Operation.SUM || operation == Operation.AVG
                || operation == Operation.MIN || operation == Operation.MAX) return true;
        return queryType == QueryType.AGGREGATE || queryType == QueryType.LIST
                || queryType == QueryType.GROUP || queryType == QueryType.SORT
                || queryType == QueryType.TOP_N;
    }

    private StructuredQueryResult compute(StructuredQueryPlan plan, MetricDefinition metric,
                                          StructuredQueryResult data) {
        List<StructuredQueryResult.Row> rows = data.getRows() == null ? List.of() : data.getRows();
        Operation op = plan.getOperation() == null ? Operation.NONE : plan.getOperation();
        Double value = null;
        switch (op) {
            case COUNT -> value = (double) rows.size();
            case COUNT_DISTINCT -> value = (double) distinctKeys(rows);
            case SUM -> value = rows.stream().mapToDouble(r -> r.getValue() == null ? 0d : r.getValue()).sum();
            case AVG -> value = rows.isEmpty() ? 0d : rows.stream()
                    .mapToDouble(r -> r.getValue() == null ? 0d : r.getValue()).average().orElse(0d);
            case MIN -> value = rows.stream().mapToDouble(r -> r.getValue() == null ? 0d : r.getValue()).min().orElse(0d);
            case MAX -> value = rows.stream().mapToDouble(r -> r.getValue() == null ? 0d : r.getValue()).max().orElse(0d);
            default -> { }
        }

        List<StructuredQueryResult.Row> outputRows = rows;
        if (plan.getSort() != null && (op == Operation.MAX || op == Operation.MIN)) {
            outputRows = new ArrayList<>(rows);
            outputRows.sort(Comparator.comparingDouble(
                    (StructuredQueryResult.Row r) -> r.getValue() == null ? 0d : r.getValue()));
            if (plan.getSort() == SortDirection.DESC) java.util.Collections.reverse(outputRows);
        }
        if (plan.getQueryType() == QueryType.TOP_N && plan.getLimit() != null && plan.getLimit() > 0) {
            List<StructuredQueryResult.Row> sorted = new ArrayList<>(rows);
            boolean desc = plan.getSort() != SortDirection.ASC;
            sorted.sort(Comparator.comparingDouble((StructuredQueryResult.Row r) -> r.getValue() == null ? 0d : r.getValue()));
            if (desc) java.util.Collections.reverse(sorted);
            outputRows = sorted.stream().limit(plan.getLimit()).toList();
        }

        return StructuredQueryResult.builder()
                .metricCode(metric.getMetricCode())
                .operation(op)
                .value(value)
                .rows(outputRows)
                .rowCount(rows.size())
                .totalEntities(data.getTotalEntities())
                .validValueCount(data.getValidValueCount())
                .missingValueCount(data.getMissingValueCount())
                .conflict(data.getConflict())
                .hasMore(data.getHasMore())
                .truncated(false)
                .build();
    }

    private long distinctKeys(List<StructuredQueryResult.Row> rows) {
        Set<String> keys = new HashSet<>();
        for (StructuredQueryResult.Row r : rows) if (r.getEntityKey() != null) keys.add(r.getEntityKey());
        return keys.size();
    }
}
