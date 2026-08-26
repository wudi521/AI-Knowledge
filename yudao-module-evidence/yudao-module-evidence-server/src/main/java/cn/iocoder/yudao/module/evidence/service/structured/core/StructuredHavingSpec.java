package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * GROUP BY 聚合后的类型化过滤条件，相当于受控 HAVING。
 *
 * <p>它只作用于当前分组的 aggregateValue，不接受字段名、SQL 或脚本，
 * 因而仍然属于查询 IR 的确定性运算原语。</p>
 */
public record StructuredHavingSpec(FilterOperator operator, List<Double> values) {
    public StructuredHavingSpec {
        if (operator == null) throw new IllegalArgumentException("having operator is required");
        values = values == null ? List.of() : List.copyOf(values);
        if (!supported(operator)) {
            throw new IllegalArgumentException("having operator is not numeric-compatible: " + operator);
        }
        if (operator == FilterOperator.BETWEEN && values.size() != 2) {
            throw new IllegalArgumentException("having BETWEEN requires exactly two values");
        }
        if (operator != FilterOperator.BETWEEN && values.isEmpty()) {
            throw new IllegalArgumentException("having values are required");
        }
        if (operator != FilterOperator.IN && operator != FilterOperator.BETWEEN && values.size() != 1) {
            throw new IllegalArgumentException("having " + operator + " requires exactly one value");
        }
        if (values.stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
            throw new IllegalArgumentException("having values must be finite numbers");
        }
    }

    private static boolean supported(FilterOperator operator) {
        return operator == FilterOperator.EQ || operator == FilterOperator.NE
                || operator == FilterOperator.GT || operator == FilterOperator.GTE
                || operator == FilterOperator.LT || operator == FilterOperator.LTE
                || operator == FilterOperator.BETWEEN || operator == FilterOperator.IN;
    }
}
