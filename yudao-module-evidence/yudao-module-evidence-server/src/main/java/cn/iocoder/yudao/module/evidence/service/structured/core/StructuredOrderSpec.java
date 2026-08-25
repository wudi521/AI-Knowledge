package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 排序表达式。可以按字段表达式、已注册指标或当前分组聚合值排序。
 */
public record StructuredOrderSpec(StructuredValueExpression value,
                                  String metricCode,
                                  boolean aggregateValue,
                                  SortDirection direction) {
    public StructuredOrderSpec {
        direction = direction == null ? SortDirection.DESC : direction;
    }
}
