package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/** AND/OR/CONDITION 组成的受控过滤树；Condition 可以作用于字段变换后的值。 */
public record StructuredPredicateNode(Type type,
                                      StructuredValueExpression value,
                                      FilterOperator operator,
                                      List<String> expected,
                                      List<StructuredPredicateNode> children) {
    public StructuredPredicateNode {
        expected = expected == null ? List.of() : List.copyOf(expected);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public enum Type { CONDITION, AND, OR }

    public static StructuredPredicateNode condition(StructuredValueExpression value,
                                                    FilterOperator operator,
                                                    List<String> expected) {
        return new StructuredPredicateNode(Type.CONDITION, value, operator, expected, List.of());
    }

    public static StructuredPredicateNode and(List<StructuredPredicateNode> children) {
        return new StructuredPredicateNode(Type.AND, null, null, List.of(), children);
    }

    public static StructuredPredicateNode or(List<StructuredPredicateNode> children) {
        return new StructuredPredicateNode(Type.OR, null, null, List.of(), children);
    }
}
