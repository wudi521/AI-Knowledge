package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * 知识库业务范围 DTO(D2: 检索硬过滤)
 */
@Data
public class KnowledgeScopeDTO {

    /** 知识库编号 */
    private Long kbId;

    /** 范围类型: PROVINCE/CITY/PRODUCT/CHANNEL/CUSTOMER_SEGMENT */
    private String scopeType;

    /** 范围编码 */
    private String scopeCode;

    /** 优先级(小者优先) */
    private Integer scopePriority;

}
