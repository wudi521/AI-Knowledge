package cn.iocoder.yudao.module.chat.controller.admin.feedback.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 回答反馈 Upsert Request VO(按 messageId 唯一, 重复提交即更新)")
@Data
public class FeedbackUpsertReqVO {

    @Schema(description = "被反馈的 AI 消息编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "3021")
    @NotNull(message = "消息编号不能为空")
    private Long messageId;

    @Schema(description = "评价: HELPFUL 有用 / NOT_HELPFUL 无用", requiredMode = Schema.RequiredMode.REQUIRED, example = "NOT_HELPFUL")
    @NotBlank(message = "评价不能为空")
    private String rating;

    @Schema(description = "无用原因(NOT_HELPFUL 必填): WRONG_ANSWER/NOT_ANSWERED/WRONG_EVIDENCE/INCOMPLETE/OUTDATED_KNOWLEDGE/TOO_VERBOSE/TOO_SLOW/OTHER", example = "WRONG_EVIDENCE")
    private String reasonCode;

    @Schema(description = "备注(可选)", example = "引用的第一页没有支撑这个结论")
    private String comment;

}
