package cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 意图 修改 Request VO")
@Data
public class IntentUpdateReqVO {

    @Schema(description = "意图编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "意图编号不能为空")
    private Long id;

    @Schema(description = "意图名", example = "保修")
    private String name;

    @Schema(description = "意图说明(LLM总结或手填, 供分类参考)", example = "保修政策咨询")
    private String description;

    @Schema(description = "状态", example = "0")
    private Integer status;

}
