package cn.iocoder.yudao.module.model.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 模型配置 新增/修改 Request VO")
@Data
public class AiModelConfigSaveReqVO {

    @Schema(description = "编号(编辑时必填)")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "BGE-M3 本地")
    @NotEmpty(message = "名称不能为空")
    private String name;

    @Schema(description = "类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "embedding")
    @NotEmpty(message = "类型不能为空")
    private String type;

    @Schema(description = "场景标识(如 A/B; *=默认场景)", example = "*")
    @NotBlank(message = "场景不能为空")
    private String scenario;

    @Schema(description = "降级顺序(小者优先)", example = "0")
    private Integer priority;

    @Schema(description = "供应商", example = "OLLAMA")
    private String provider;

    @Schema(description = "模型标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "bge-m3")
    @NotEmpty(message = "模型标识不能为空")
    private String modelName;

    @Schema(description = "服务地址", example = "http://127.0.0.1:11434")
    private String baseUrl;

    @Schema(description = "API 密钥")
    private String apiKey;

    @Schema(description = "向量维度", example = "1024")
    private Integer dimensions;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

}
