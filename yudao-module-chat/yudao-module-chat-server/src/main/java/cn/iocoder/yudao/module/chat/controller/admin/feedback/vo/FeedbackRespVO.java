package cn.iocoder.yudao.module.chat.controller.admin.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - AI 回答反馈 Response VO")
@Data
public class FeedbackRespVO {

    @Schema(description = "反馈编号")
    private Long id;

    @Schema(description = "被反馈的 AI 消息编号")
    private Long messageId;

    @Schema(description = "会话编号")
    private Long conversationId;

    @Schema(description = "统一主追踪号(q- 前缀)")
    private String queryTraceId;

    @Schema(description = "知识库编号")
    private Long kbId;

    @Schema(description = "知识领域编码")
    private String domainCode;

    @Schema(description = "评价: HELPFUL/NOT_HELPFUL")
    private String rating;

    @Schema(description = "无用原因")
    private String reasonCode;

    @Schema(description = "备注")
    private String comment;

    @Schema(description = "回答路由")
    private String route;

    @Schema(description = "意图")
    private String intent;

    @Schema(description = "置信度(0~1)")
    private BigDecimal confidence;

    @Schema(description = "回答耗时(ms)")
    private Long latencyMs;

    @Schema(description = "主证据文档编号")
    private Long primaryDocumentId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
