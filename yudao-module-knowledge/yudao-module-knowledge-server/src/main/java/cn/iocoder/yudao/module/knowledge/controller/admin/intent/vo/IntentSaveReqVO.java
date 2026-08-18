package cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 意图 新增 Request VO")
@Data
public class IntentSaveReqVO {

    @Schema(description = "知识库编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "知识库编号不能为空")
    private Long kbId;

    @Schema(description = "意图名", requiredMode = Schema.RequiredMode.REQUIRED, example = "保修")
    @NotBlank(message = "意图名不能为空")
    private String name;

    @Schema(description = "意图说明(LLM总结或手填, 供分类参考)", example = "保修政策咨询")
    private String description;

}
