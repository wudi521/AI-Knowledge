package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 类型化 Structured Filter Tree。
 * CONDITION 节点只允许 DomainFieldRegistry 已注册字段；AND/OR 仅组合子节点。
 */
@Data
@Builder
public class FilterExpression {

    public enum Type { CONDITION, AND, OR }

    private Type type;
    private String fieldCode;
    private FilterOperator operator;

    @Builder.Default
    private List<String> values = new ArrayList<>();

    @Builder.Default
    private List<FilterExpression> children = new ArrayList<>();

    public static FilterExpression condition(String fieldCode, FilterOperator operator, List<String> values) {
        return FilterExpression.builder().type(Type.CONDITION).fieldCode(fieldCode)
                .operator(operator).values(values == null ? List.of() : values).build();
    }

    public static FilterExpression and(List<FilterExpression> children) {
        return FilterExpression.builder().type(Type.AND).children(children).build();
    }

    public static FilterExpression or(List<FilterExpression> children) {
        return FilterExpression.builder().type(Type.OR).children(children).build();
    }
}
