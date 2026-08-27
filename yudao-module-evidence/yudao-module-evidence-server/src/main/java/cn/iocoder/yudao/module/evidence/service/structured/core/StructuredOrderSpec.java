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
        int sources = 0;
        if (value != null) sources++;
        if (metricCode != null && !metricCode.isBlank()) sources++;
        if (aggregateValue) sources++;
        if (sources == 0) throw new IllegalArgumentException("order-by source is missing");
        if (sources > 1) throw new IllegalArgumentException("order-by must declare exactly one source");
    }
}
