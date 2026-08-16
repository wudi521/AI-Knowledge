package cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - AI 知识片段分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ChunkPageReqVO extends PageParam {

    @Schema(description = "文档编号(映射 ai_chunk.version_id)", example = "1024")
    private Long documentId;

    @Schema(description = "片段类型", example = "SEMANTIC")
    private String chunkType;

    @Schema(description = "状态", example = "PUBLISHED")
    private String status;

}
