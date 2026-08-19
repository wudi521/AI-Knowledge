package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 知识库槽位定义 新增/修改 Request VO")
@Data
public class AiKnowledgeBaseSlotSaveReqVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "知识库编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "知识库编号不能为空")
    private Long kbId;

    @Schema(description = "槽位编码(如 brand/faultType/purchaseTime)", requiredMode = Schema.RequiredMode.REQUIRED, example = "brand")
    @NotBlank(message = "槽位编码不能为空")
    private String slotCode;

    @Schema(description = "槽位名(如 品牌型号)", requiredMode = Schema.RequiredMode.REQUIRED, example = "品牌型号")
    @NotBlank(message = "槽位名不能为空")
    private String slotName;

    @Schema(description = "抽取说明(喂给 LLM 的定义)", requiredMode = Schema.RequiredMode.REQUIRED, example = "从用户描述中提取产品品牌与型号")
    @NotBlank(message = "抽取说明不能为空")
    private String description;

    @Schema(description = "是否必填(1=缺则反问)", example = "true")
    private Boolean required;

    @Schema(description = "排序(组反问句顺序)", example = "1")
    private Integer sort;

    @Schema(description = "状态: 0=启用 1=禁用", example = "0")
    private Integer status;

}
