package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 结构化查询结果回流(CQ-02/03): 携带保序实体 id 供 chat 侧形成 ResultSetSnapshot。
 */
@Data
public class StructuredResultDTO {

    /** 保序实体 id(如 documentId) */
    private List<Long> entityIds;

    /** 实体展示键(与 entityIds 对齐, 如申请号/公布号; 可空) */
    private List<String> entityKeys;

    /** 实体类型 */
    private String entityType;

    /** 指标编码(metric 查询) */
    private String metricCode;

    /** 字段编码(字段查询) */
    private String fieldCode;

    /** 聚合运算 */
    private String operation;

    /** 查询类型(EXACT_LOOKUP/AGGREGATE/LIST/GROUP/SORT/TOP_N) */
    private String queryType;

    /** 范围类型(CURRENT_KB/DOCUMENT_SET/...) */
    private String scopeType;

    /** 是否截断 */
    private Boolean truncated;

    /** 实体总数 */
    private Integer entityCount;

}
