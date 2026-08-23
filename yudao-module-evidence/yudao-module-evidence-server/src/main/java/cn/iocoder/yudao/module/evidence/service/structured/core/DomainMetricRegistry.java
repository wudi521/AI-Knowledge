package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.Optional;

/**
 * Domain Metric Registry SPI(Platform Core 通过 domainCode + metricCode 查指标定义)。
 * <p>
 * Patent/Telecom/Manufacturing 等 Domain Pack 在此注册指标; Core 只读不感知具体业务。
 */
public interface DomainMetricRegistry {

    /** 按 domainCode + metricCode 查指标(未注册 → empty) */
    Optional<MetricDefinition> lookup(String domainCode, String metricCode);

    /** 在指定领域内按同义词查指标(如 "权利要求数量" → CLAIM_COUNT) */
    Optional<MetricDefinition> findByAlias(String domainCode, String alias);

    /** 注册指标(同 domainCode+metricCode 覆盖) */
    void register(MetricDefinition definition);

    /** 该领域已注册的全部指标(供 Planner 做同义词最长匹配) */
    java.util.Collection<MetricDefinition> all(String domainCode);

}
