package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Map;

/** 完全内存、无脚本的白名单 Filter Tree 执行器。 */
public final class StructuredFilterEvaluator {

    private StructuredFilterEvaluator() {}

    public static boolean matches(StructuredQueryResult.Row row, FilterExpression expression) {
        if (expression == null) return true;
        if (row == null || expression.getType() == null) return false;
        return switch (expression.getType()) {
            case AND -> children(expression).stream().allMatch(child -> matches(row, child));
            case OR -> !children(expression).isEmpty()
                    && children(expression).stream().anyMatch(child -> matches(row, child));
            case CONDITION -> matchCondition(row, expression);
        };
    }

    private static boolean matchCondition(StructuredQueryResult.Row row, FilterExpression expression) {
        String field = expression.getFieldCode();
        FilterOperator operator = expression.getOperator();
        if (StrUtil.isBlank(field) || operator == null) return false;
        Map<String, String> fields = row.getFields() == null ? Map.of() : row.getFields();
        String actual = fields.get(field);
        List<String> expected = expression.getValues() == null ? List.of() : expression.getValues();
        return switch (operator) {
            case EXISTS -> StrUtil.isNotBlank(actual);
            case EQ -> actual != null && !expected.isEmpty() && actual.equalsIgnoreCase(expected.get(0));
            case NE -> actual == null || expected.isEmpty() || !actual.equalsIgnoreCase(expected.get(0));
            case CONTAINS -> actual != null && !expected.isEmpty()
                    && actual.toLowerCase().contains(expected.get(0).toLowerCase());
            case STARTS_WITH -> actual != null && !expected.isEmpty()
                    && actual.toLowerCase().startsWith(expected.get(0).toLowerCase());
            case IN -> actual != null && expected.stream().anyMatch(v -> actual.equalsIgnoreCase(v));
        };
    }

    private static List<FilterExpression> children(FilterExpression expression) {
        return expression.getChildren() == null ? List.of() : expression.getChildren();
    }
}
