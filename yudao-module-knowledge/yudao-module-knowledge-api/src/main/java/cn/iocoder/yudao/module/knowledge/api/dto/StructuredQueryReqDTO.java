package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * Structured Query 数据访问请求(Platform Core → Domain Data Adapter → Knowledge 数据访问)。
 * <p>
 * 白名单化: 仅支持按 kbId + 已发布 + 领域 + 已解析文档集合过滤, 禁止任意 SQL/字段访问。
 */
@Data
public class StructuredQueryReqDTO {

    /** 知识库编号(必填; 权限已在调用方裁剪) */
    private Long kbId;

    /** 指标编码(白名单: DOCUMENT_COUNT / CLAIM_COUNT ...) */
    private String metricCode;

    /** 仅统计已发布版本文档 */
    private Boolean publishedOnly;

    /** 已解析的文档集合(DOCUMENT_SET 范围; 空 = 整库) */
    private List<Long> resolvedEntityIds;

    /** 单次返回行数上限(防御性截断标记用) */
    private Integer rowCap;

}
