package cn.iocoder.yudao.module.chat.controller.admin.chat;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.service.chat.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 对话")
@RestController
@RequestMapping("/chat/chat")
@Validated
public class AiChatController {

    @Resource
    private AiChatService aiChatService;

    @PostMapping("/send")
    @Operation(summary = "发送消息(SSE 流式见 TODO)")
    @PreAuthorize("@ss.hasPermission('chat:chat:query')")
    public CommonResult<String> send(@RequestParam("conversationId") Long conversationId,
                                     @RequestParam("message") String message) {
        return success(aiChatService.sendMessage(conversationId, message));
    }

}
