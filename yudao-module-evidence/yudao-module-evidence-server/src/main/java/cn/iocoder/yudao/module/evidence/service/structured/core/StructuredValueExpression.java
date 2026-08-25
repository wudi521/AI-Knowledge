package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * 一个受控值表达式：从已注册字段读取值，可选择展开多值并应用白名单变换。
 */
public record StructuredValueExpression(String fieldCode,
                                        boolean explode,
                                        List<StructuredValueTransform> transforms) {
    public StructuredValueExpression {
        transforms = transforms == null ? List.of() : List.copyOf(transforms);
    }

    public static StructuredValueExpression field(String fieldCode) {
        return new StructuredValueExpression(fieldCode, false, List.of());
    }
}
