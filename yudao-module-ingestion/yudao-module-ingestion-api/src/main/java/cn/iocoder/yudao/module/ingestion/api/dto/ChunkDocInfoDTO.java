package cn.iocoder.yudao.module.ingestion.api.dto;

import lombok.Data;

/**
 * 片段文档信息 RPC DTO(检索结果补全: chunkId -> 来源文档/版本)
 */
@Data
public class ChunkDocInfoDTO {

    /** 片段编号 */
    private Long chunkId;

    /** 文档编号 */
    private Long documentId;

    /** 文档名 */
    private String documentName;

    /** 版本号: V1/V2/... */
    private String versionNo;

}
