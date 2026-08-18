package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 会话历史记录 Response VO")
@Data
public class ConversationHistoryRespVO {

    @Schema(description = "会话信息")
    private ConversationInfoVO conversation;

    @Schema(description = "消息列表(按创建时间升序)")
    private List<MessageVO> messages;

}
