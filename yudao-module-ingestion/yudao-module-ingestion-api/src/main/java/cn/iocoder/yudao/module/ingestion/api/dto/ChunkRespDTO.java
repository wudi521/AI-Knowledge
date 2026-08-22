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

    /** 类型: SEMANTIC/TABLE/FAQ/POLICY/STRUCTURE/PATENT_CLAIM */
    private String chunkType;

    /** 角色: PARENT/CHILD/LEAF/TABLE/IMAGE */
    private String chunkRole;

    /** 状态: REVIEW/PUBLISHED/DISABLED */
    private String status;

    /** 元数据(JSON; 专利: sectionType/claimNo/pageStart 等) */
    private String metadata;

}
