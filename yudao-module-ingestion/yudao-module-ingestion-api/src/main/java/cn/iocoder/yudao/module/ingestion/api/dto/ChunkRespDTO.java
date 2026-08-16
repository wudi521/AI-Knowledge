package cn.iocoder.yudao.module.ingestion.api.dto;

import lombok.Data;

/**
 * 片段 RPC DTO(knowledge 抽取条目用)
 */
@Data
public class ChunkRespDTO {

    /** 编号 */
    private Long id;

    /** 版本编号 */
    private Long versionId;

    /** 片段内容 */
    private String content;

    /** 类型: SEMANTIC/TABLE/FAQ/POLICY */
    private String chunkType;

}
