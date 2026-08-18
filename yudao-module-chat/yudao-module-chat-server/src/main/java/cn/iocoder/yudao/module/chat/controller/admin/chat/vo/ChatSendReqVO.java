package cn.iocoder.yudao.module.chat.controller.admin.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "管理后台 - AI 对话发送 Request VO")
@Data
public class ChatSendReqVO {

    @Schema(description = "会话编号(为空则新建会话)", example = "1")
    private Long conversationId;

    @Schema(description = "客户消息", requiredMode = Schema.RequiredMode.REQUIRED, example = "X100 Pro 碎屏能免费修吗")
    @NotBlank(message = "消息不能为空")
    private String message;

    @Schema(description = "渠道(为空默认 WEB)", example = "WEB")
    private String channel;

    @Schema(description = "客户标识(新建会话时使用)", example = "customer-1")
    private String customerId;

}
