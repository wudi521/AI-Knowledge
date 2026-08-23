package cn.iocoder.yudao.module.chat.controller.admin.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Schema(description = "管理后台 - AI 回答反馈统计 VO(P0 仅计数与 rate)")
@Data
@Builder
public class FeedbackStatsRespVO {

    @Schema(description = "反馈总数")
    private Long totalCount;

    @Schema(description = "有用数(HELPFUL)")
    private Long helpfulCount;

    @Schema(description = "无用数(NOT_HELPFUL)")
    private Long notHelpfulCount;

    @Schema(description = "有用率(0~1)")
    private Double helpfulRate;

    @Schema(description = "无效率(0~1)")
    private Double notHelpfulRate;

}
