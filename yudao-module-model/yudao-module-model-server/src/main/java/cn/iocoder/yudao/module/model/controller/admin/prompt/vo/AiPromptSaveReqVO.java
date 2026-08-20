package cn.iocoder.yudao.module.model.controller.admin.prompt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "管理后台 - AI Prompt 新增 Request VO")
@Data
public class AiPromptSaveReqVO {

    @Schema(description = "业务键(如 query-analysis/slot-detect)", requiredMode = Schema.RequiredMode.REQUIRED, example = "query-analysis")
    @NotBlank(message = "业务键不能为空")
    private String promptKey;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "查询分析提示词")
    @NotBlank(message = "名称不能为空")
    private String name;

    @Schema(description = "说明", example = "用于查询意图分析")
    private String description;

    @Schema(description = "提示词内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "你是查询分析专家...")
    @NotBlank(message = "提示词内容不能为空")
    private String content;

}
