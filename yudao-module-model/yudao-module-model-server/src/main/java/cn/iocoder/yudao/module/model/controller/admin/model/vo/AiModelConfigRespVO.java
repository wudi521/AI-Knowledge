package cn.iocoder.yudao.module.model.controller.admin.model.vo;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @Schema(description = "场景标识(如 A/B; *=默认场景)", example = "*")
    private String scenario;

    @Schema(description = "降级顺序(小者优先)", example = "0")
    private Integer priority;

    @Schema(description = "供应商", example = "OLLAMA")
    private String provider;

    @Schema(description = "模型标识", example = "bge-m3")
    private String modelName;

    @Schema(description = "服务地址", example = "http://127.0.0.1:11434")
    private String baseUrl;

    /**
     * 明文 API 密钥(仅服务端内部使用; 序列化时脱敏为 {@link #getMaskedApiKey()})
     */
    @JsonIgnore
    private String apiKey;

    /** 脱敏后的密钥(如 sk-****abcd; 空密钥显示 "未配置") */
    public String getMaskedApiKey() {
        if (StrUtil.isBlank(apiKey)) {
            return "未配置";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @Schema(description = "向量维度", example = "1024")
    private Integer dimensions;

    @Schema(description = "状态", example = "1")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
