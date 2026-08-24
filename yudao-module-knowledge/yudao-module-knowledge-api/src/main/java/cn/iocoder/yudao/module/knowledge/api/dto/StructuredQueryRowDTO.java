package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/** Structured Query 数据行(每对象一行, 完整结构化数据集)。 */
@Data
public class StructuredQueryRowDTO {

    /** 对象编号(documentId) */
    private Long documentId;
    /** 对象名(文档名/专利标题) */
    private String documentName;
    /** 对象键(如申请号; COUNT_DISTINCT 去重用) */
    private String applicationNo;
    /** 公布号 */
    private String publicationNo;
    /** 申请人，多值时由 Domain Adapter 负责展示约定。 */
    private String applicant;
    /** 发明人，多值时由 Domain Adapter 负责展示约定。 */
    private String inventor;
    /** 申请日 */
    private String filingDate;
    /** 公布日 */
    private String publicationDate;
    /** 该对象指标值(如 claimCount; DOCUMENT_COUNT 恒为 1) */
    private Double value;
}
