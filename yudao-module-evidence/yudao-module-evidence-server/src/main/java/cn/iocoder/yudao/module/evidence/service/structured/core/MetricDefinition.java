package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 指标定义(Platform Core 只通过 domainCode + metricCode 读取, 不感知具体业务)。
 * <p>
 * Source of Truth 必须是 Domain Registry, 禁止只写进 query-analysis prompt。
 */
@Data
@Builder
public class MetricDefinition {

    /** 指标编码(如 CLAIM_COUNT / DOCUMENT_COUNT) */
    private String metricCode;

    /** 领域编码(如 PATENT) */
    private String domainCode;

    /** 度量实体类型(如 PATENT_DOCUMENT) */
    private String entityType;

    /**
     * 指标读取/计数所在的数据粒度。
     * LOGICAL_ENTITY=按领域身份去重后的业务实体；SOURCE_RECORD=知识库中的物理记录。
     */
    @Builder.Default
    private DataGrain dataGrain = DataGrain.LOGICAL_ENTITY;

    /** 值类型: INTEGER / DECIMAL */
    private String valueType;

    /** 支持的运算(COUNT / COUNT_DISTINCT / SUM / AVG / MIN / MAX) */
    private Set<Operation> supportedOperations;

    /** 支持的分组维度 */
    private List<String> supportedGroupBy;

    /** 指标同义词(如 CLAIM_COUNT: 权利要求数量/权项数/专利要求数量) */
    private List<String> aliases;

    /** 展示名(答案模板用; 如 权利要求 / 专利文献) */
    private String displayName;

    /** 计量单位(如 项 / 件) */
    private String unit;

    /** 描述 */
    private String description;

    /** 数据访问适配器键(如 PATENT; 由 DomainStructuredDataAdapter 实现) */
    private String adapterKey;

    /**
     * Planner 目前通过 description 消费指标语义，因此把 grain 同步进可读契约；
     * 机器执行仍读取 dataGrain 字段本身，不能依赖文字解析。
     */
    public String getDescription() {
        String base = description == null ? "" : description;
        return base + (base.isBlank() ? "" : "; ") + "dataGrain=" + dataGrain;
    }
}
