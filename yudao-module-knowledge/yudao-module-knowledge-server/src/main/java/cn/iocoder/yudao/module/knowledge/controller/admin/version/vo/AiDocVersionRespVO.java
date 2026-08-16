package cn.iocoder.yudao.module.knowledge.controller.admin.version.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 文档版本 Response VO")
@Data
public class AiDocVersionRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "文档编号")
    private Long docId;

    @Schema(description = "版本号", example = "V1")
    private String versionNo;

    @Schema(description = "状态: DRAFT/REVIEW/PUBLISHED/EXPIRED/ARCHIVED")
    private String status;

    @Schema(description = "生效开始时间")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效结束时间")
    private LocalDateTime effectiveTo;

    @Schema(description = "审核人")
    private String reviewer;

    @Schema(description = "冲突状态: 0无 1待裁决 2已裁决")
    private Integer conflictStatus;

    @Schema(description = "审核结果: APPROVED/REJECTED")
    private String reviewResult;

    @Schema(description = "审核意见")
    private String reviewComment;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
