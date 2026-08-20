package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/**
 * 知识库已发布片段采样 DTO(供评测自动生成用例等消费: 内容 + 片段编号)
 */
@Data
public class KnowledgePublishedChunkDTO {

    /** 片段编号(goldChunks 标准证据引用) */
    private Long chunkId;

    /** 片段内容(已按采样上限截断) */
    private String content;

}
