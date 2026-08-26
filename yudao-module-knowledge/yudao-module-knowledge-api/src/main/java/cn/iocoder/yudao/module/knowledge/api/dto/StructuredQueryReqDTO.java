package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Structured Query 数据访问请求(Platform Core → Domain Data Adapter → Knowledge 数据访问)。
 * <p>
 * 白名单化: 仅支持按 kbId + 已发布 + 领域 + 已解析文档集合过滤, 禁止任意 SQL/字段访问。
 */
@Data
public class StructuredQueryReqDTO {

    /** 知识库编号(必填; 权限已在调用方裁剪) */
    private Long kbId;

    /** 领域编码；分页数据源据此选择服务端白名单物理映射。 */
    private String domainCode;

    /** 指标编码(白名单: DOCUMENT_COUNT / CLAIM_COUNT ...) */
    private String metricCode;

    /** 仅统计已发布版本文档 */
    private Boolean publishedOnly;

    /** 已解析的文档集合(DOCUMENT_SET 范围; 空 = 整库) */
    private List<Long> resolvedEntityIds;

    /** 字段编码(按字段取值 LIST/GROUP, 如 PUBLICATION_NO; 空 = 按 metric 聚合) */
    private String fieldCode;

    /** 过滤条件(Map<fieldCode, value>; 仅支持等值, CQ-19 Filter follow-up) */
    private Map<String, String> filters;

    /** 排序: 逗号分隔 "fieldCode:ASC|DESC" (如 "filingDate:ASC", CQ-18) */
    private String sort;

    /** 单次返回行数上限；分页接口会服务端钳制，不代表最终全集上限。 */
    private Integer rowCap;

    /** keyset 分页游标：只返回 documentId > afterDocumentId 的下一页。 */
    private Long afterDocumentId;

}
