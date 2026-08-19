package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * 知识库槽位定义 DTO(槽位检测 RPC 消费)
 */
@Data
public class KnowledgeSlotDefinitionDTO {

    /** 知识库编号 */
    private Long kbId;
    /** 槽位编码(如 brand/faultType/purchaseTime) */
    private String slotCode;
    /** 槽位名(如 品牌型号) */
    private String slotName;
    /** 抽取说明(喂给 LLM 的定义) */
    private String description;
    /** 是否必填 */
    private Boolean required;
    /** 排序 */
    private Integer sort;

}
