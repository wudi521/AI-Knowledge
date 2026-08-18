package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 会话手动转人工 Request VO")
@Data
public class ConversationTransferReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "会话编号不能为空")
    private Long conversationId;

    @Schema(description = "转人工原因(为空兜底'人工转接')", example = "客户要求高级专员处理")
    private String reason;

}
