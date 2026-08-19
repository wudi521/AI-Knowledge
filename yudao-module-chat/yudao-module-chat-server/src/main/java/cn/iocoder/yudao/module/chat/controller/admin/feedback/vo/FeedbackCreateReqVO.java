package cn.iocoder.yudao.module.chat.controller.admin.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 反馈创建 Request VO")
@Data
public class FeedbackCreateReqVO {

    @Schema(description = "消息编号(被反馈的 AI 消息)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "消息编号不能为空")
    private Long messageId;

    @Schema(description = "反馈类型: THUMB_UP 点赞 / THUMB_DOWN 点踩", requiredMode = Schema.RequiredMode.REQUIRED, example = "THUMB_DOWN")
    @NotBlank(message = "反馈类型不能为空")
    private String type;

    @Schema(description = "说明(点踩原因等)", example = "回答不准确")
    private String note;

}
