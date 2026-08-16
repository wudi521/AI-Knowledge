package cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 知识片段状态更新 Request VO")
@Data
public class ChunkUpdateStatusReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "状态(PUBLISHED=启用 / DISABLED=禁用)", requiredMode = Schema.RequiredMode.REQUIRED, example = "PUBLISHED")
    @NotBlank(message = "状态不能为空")
    private String status;

}
