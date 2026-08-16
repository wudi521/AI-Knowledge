package cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 知识片段更新 Request VO")
@Data
public class ChunkUpdateReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "片段内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "片段内容不能为空")
    private String content;

}
