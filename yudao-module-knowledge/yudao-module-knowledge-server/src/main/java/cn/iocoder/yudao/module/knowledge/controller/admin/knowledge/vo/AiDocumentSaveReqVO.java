package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 文档 新增 Request VO")
@Data
public class AiDocumentSaveReqVO {

    @Schema(description = "知识库编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "知识库编号不能为空")
    private Long kbId;

    @Schema(description = "文档名", requiredMode = Schema.RequiredMode.REQUIRED, example = "退换货政策.md")
    @NotEmpty(message = "文档名不能为空")
    private String name;

    @Schema(description = "类型", example = "MD")
    private String type;

    @Schema(description = "存储路径(MinIO)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "存储路径不能为空")
    private String storagePath;

    @Schema(description = "文件 SHA-256(前端上传时计算, 用于重复文档拦截)")
    private String fileHash;

    @Schema(description = "切分策略", example = "auto")
    private String chunkStrategy;

    @Schema(description = "切分策略参数(JSON, 可选; 如 {\"maxTokens\":500,\"overlap\":1})")
    private String chunkStrategyParams;

}
