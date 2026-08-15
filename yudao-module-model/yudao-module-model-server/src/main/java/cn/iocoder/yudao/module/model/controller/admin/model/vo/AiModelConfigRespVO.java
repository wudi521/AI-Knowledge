package cn.iocoder.yudao.module.model.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 模型配置 Response VO")
@Data
public class AiModelConfigRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "名称", example = "BGE-M3 本地")
    private String name;

    @Schema(description = "类型", example = "embedding")
    private String type;

    @Schema(description = "供应商", example = "OLLAMA")
    private String provider;

    @Schema(description = "模型标识", example = "bge-m3")
    private String modelName;

    @Schema(description = "服务地址", example = "http://127.0.0.1:11434")
    private String baseUrl;

    @Schema(description = "API 密钥")
    private String apiKey;

    @Schema(description = "向量维度", example = "1024")
    private Integer dimensions;

    @Schema(description = "状态", example = "1")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
