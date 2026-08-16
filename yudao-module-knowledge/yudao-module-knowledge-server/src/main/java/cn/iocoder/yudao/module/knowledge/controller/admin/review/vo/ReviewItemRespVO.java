package cn.iocoder.yudao.module.knowledge.controller.admin.review.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 审核条目 Response VO")
@Data
public class ReviewItemRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "版本编号")
    private Long versionId;

    @Schema(description = "文档编号")
    private Long docId;

    @Schema(description = "文档名称(联表)")
    private String docName;

    @Schema(description = "来源Chunk编号")
    private Long chunkId;

    @Schema(description = "条目类型")
    private String itemType;

    @Schema(description = "条目主题")
    private String title;

    @Schema(description = "条目内容")
    private String content;

    @Schema(description = "风险等级")
    private String riskLevel;

    @Schema(description = "AI置信度")
    private BigDecimal aiConfidence;

    @Schema(description = "是否必审")
    private Boolean mustReview;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "审核人")
    private String reviewer;

    @Schema(description = "双人复核第二人")
    private String reviewer2;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

}
