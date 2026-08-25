package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
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
            case GT -> comparable(actual, expected, comparison -> comparison > 0);
            case GTE -> comparable(actual, expected, comparison -> comparison >= 0);
            case LT -> comparable(actual, expected, comparison -> comparison < 0);
            case LTE -> comparable(actual, expected, comparison -> comparison <= 0);
            case BETWEEN -> actual != null && expected.size() >= 2
                    && compare(actual, expected.get(0)) >= 0 && compare(actual, expected.get(1)) <= 0;
        };
    }

    private static boolean comparable(String actual, List<String> expected,
                                      java.util.function.IntPredicate predicate) {
        return actual != null && !expected.isEmpty() && predicate.test(compare(actual, expected.get(0)));
    }

    /** 数值优先、ISO 日期其次、最后按大小写无关字符串比较。 */
    private static int compare(String left, String right) {
        if (left == null || right == null) return Integer.MIN_VALUE;
        try {
            return new BigDecimal(left.trim()).compareTo(new BigDecimal(right.trim()));
        } catch (NumberFormatException ignore) {
            // 非数值继续尝试日期
        }
        LocalDate leftDate = date(left);
        LocalDate rightDate = date(right);
        if (leftDate != null && rightDate != null) return leftDate.compareTo(rightDate);
        return left.trim().toLowerCase(Locale.ROOT).compareTo(right.trim().toLowerCase(Locale.ROOT));
    }

    private static LocalDate date(String value) {
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("yyyy.MM.dd"))) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignore) {
                // 尝试下一种允许格式
            }
        }
        return null;
    }

    private static List<FilterExpression> children(FilterExpression expression) {
        return expression.getChildren() == null ? List.of() : expression.getChildren();
    }
}
