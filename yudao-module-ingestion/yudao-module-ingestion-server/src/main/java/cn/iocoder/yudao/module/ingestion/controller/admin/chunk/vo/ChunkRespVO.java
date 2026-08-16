package cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 知识片段 Response VO")
@Data
public class ChunkRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "文档编号(映射自 ai_chunk.version_id)", example = "1024")
    private Long documentId;

    @Schema(description = "所属文档名称")
    private String documentName;

    @Schema(description = "文档存储路径(MinIO, 供下载)")
    private String storagePath;

    @Schema(description = "片段类型", example = "SEMANTIC")
    private String chunkType;

    @Schema(description = "片段内容")
    private String content;

    @Schema(description = "父片段编号", example = "1024")
    private Long parentId;

    @Schema(description = "元数据")
    private String metadata;

    @Schema(description = "状态", example = "PUBLISHED")
    private String status;

    @Schema(description = "Milvus 向量关联键")
    private String vectorKey;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
