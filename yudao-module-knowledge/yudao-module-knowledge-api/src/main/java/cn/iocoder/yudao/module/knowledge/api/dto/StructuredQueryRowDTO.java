package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/** Structured Query 数据行(每对象一行, 完整结构化数据集)。 */
@Data
public class StructuredQueryRowDTO {

    /** 对象编号(documentId) */
    private Long documentId;
    /** 物理文档名/上传文件名；领域实体标题必须使用 title。 */
    private String documentName;
    /** 领域实体标题；PATENT 为 domain_metadata.title，不等同于上传文件名。 */
    private String title;
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
