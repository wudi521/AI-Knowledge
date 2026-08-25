package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 聚合表达式。value 与 metricCode 二选一；COUNT 允许两者都为空，表示逻辑实体计数。
 */
public record StructuredAggregateSpec(Operation operation,
                                      StructuredValueExpression value,
                                      String metricCode) {
}
