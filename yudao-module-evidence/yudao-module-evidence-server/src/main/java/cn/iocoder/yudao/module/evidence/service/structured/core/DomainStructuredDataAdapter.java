package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Domain Structured Data Adapter SPI(Platform Core 通过 adapterKey 路由到对应 Domain Pack 实现)。
 * <p>
 * Core 只面向 {@link StructuredQueryPlan} 执行; 具体指标的数据访问由 Domain Pack 适配器完成。
 * Patent/Telecom/Manufacturing 各自实现, Platform Core 不修改。
 */
public interface DomainStructuredDataAdapter {

    /** 适配器键(与 MetricDefinition.adapterKey 对应; 如 PATENT) */
    String adapterKey();

    /** 是否支持该指标(未提取数据源等不支持时返回 false) */
    boolean supports(String metricCode);

    /**
     * 按 plan 返回范围内完整结构化数据集(rows)。
     * scope/metric/filters 由 Domain Pack 翻译为安全的、白名单化的数据访问(禁止任意 SQL)。
     */
    StructuredQueryResult execute(StructuredQueryPlan plan);

}
