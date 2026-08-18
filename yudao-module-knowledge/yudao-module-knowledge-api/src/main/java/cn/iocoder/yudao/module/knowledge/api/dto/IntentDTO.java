package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * 知识库意图 RPC DTO(检索平台分类参考用)
 */
@Data
public class IntentDTO {

    /** 知识库编号 */
    private Long kbId;

    /** 意图名(如 保修/退款/产品推荐) */
    private String name;

    /** 意图说明(LLM总结或手填, 供分类参考) */
    private String description;

}
