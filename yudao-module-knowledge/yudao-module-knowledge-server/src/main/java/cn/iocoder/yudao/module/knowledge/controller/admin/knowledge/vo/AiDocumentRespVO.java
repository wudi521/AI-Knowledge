package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 文档 Response VO")
@Data
public class AiDocumentRespVO {

    @Schema(description = "编号", example = "1024")
    private Long id;

    @Schema(description = "知识库编号")
    private Long kbId;

    @Schema(description = "知识库名称")
    private String kbName;

    @Schema(description = "切分策略", example = "auto")
    private String chunkStrategy;

    @Schema(description = "切分策略参数(JSON)")
    private String chunkStrategyParams;

    @Schema(description = "领域文档元数据(JSON; 专利著录信息等)")
    private String domainMetadata;

    @Schema(description = "文档名", example = "退换货政策.md")
    private String name;

    @Schema(description = "类型", example = "MD")
    private String type;

    @Schema(description = "存储路径(MinIO)")
    private String storagePath;

    @Schema(description = "文件 SHA-256")
    private String fileHash;

    @Schema(description = "解析状态", example = "PENDING")
    private String parseStatus;

    @Schema(description = "切分片段数(解析结果)")
    private Integer chunkCount;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "上传人")
    private String owner;

    @Schema(description = "当前版本编号")
    private Long versionId;

    @Schema(description = "当前版本号", example = "V3")
    private String versionNo;

    @Schema(description = "当前版本状态", example = "REVIEW")
    private String versionStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
