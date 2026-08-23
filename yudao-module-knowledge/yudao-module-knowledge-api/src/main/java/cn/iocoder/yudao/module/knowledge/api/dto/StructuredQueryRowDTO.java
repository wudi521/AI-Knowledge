package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * Structured Query 数据行(每对象一行, 完整结构化数据集)。
 */
@Data
public class StructuredQueryRowDTO {

    /** 对象编号(documentId) */
    private Long documentId;

    /** 对象名(文档名) */
    private String documentName;

    /** 对象键(如申请号; COUNT_DISTINCT 去重用) */
    private String applicationNo;

    /** 公布号 */
    private String publicationNo;

    /** 该对象指标值(如 claimCount; DOCUMENT_COUNT 恒为 1) */
    private Double value;

}
