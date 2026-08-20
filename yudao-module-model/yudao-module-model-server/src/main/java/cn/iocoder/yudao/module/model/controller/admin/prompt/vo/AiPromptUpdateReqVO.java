package cn.iocoder.yudao.module.model.controller.admin.prompt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI Prompt 更新 Request VO(仅停用版本可编辑)")
@Data
public class AiPromptUpdateReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "查询分析提示词")
    @NotBlank(message = "名称不能为空")
    private String name;

    @Schema(description = "说明", example = "用于查询意图分析")
    private String description;

    @Schema(description = "提示词内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "你是查询分析专家...")
    @NotBlank(message = "提示词内容不能为空")
    private String content;

}
