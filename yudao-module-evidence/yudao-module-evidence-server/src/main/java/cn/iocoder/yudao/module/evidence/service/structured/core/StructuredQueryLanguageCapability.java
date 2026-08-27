package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * Planner 可读取的 Query IR 语言能力，而不是“某个用户问题 -> 某个固定算子”的意图表。
 *
 * <p>字段、指标及字段级 transform 仍由 Domain Schema 提供；这里仅声明 Runtime 能解释/执行的
 * 通用关系/计算语言边界。有限语言原语可以自由组合，不能把字段 + 算子 + transform 的组合
 * 重新枚举成 Planner 菜单。</p>
 */
public record StructuredQueryLanguageCapability(String domainCode,
                                                String irVersion,
                                                List<String> clauses,
                                                List<String> expressionKinds,
                                                List<String> aggregateFunctions,
                                                List<String> predicateOperators,
                                                List<String> resultShapes,
                                                List<String> executionModes) {
    public StructuredQueryLanguageCapability {
        clauses = immutable(clauses);
        expressionKinds = immutable(expressionKinds);
        aggregateFunctions = immutable(aggregateFunctions);
        predicateOperators = immutable(predicateOperators);
        resultShapes = immutable(resultShapes);
        executionModes = immutable(executionModes);
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }
}
