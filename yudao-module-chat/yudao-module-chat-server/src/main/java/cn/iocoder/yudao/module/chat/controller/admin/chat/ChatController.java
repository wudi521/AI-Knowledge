package cn.iocoder.yudao.module.chat.controller.admin.chat;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatSendReqVO;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatSendRespVO;
import cn.iocoder.yudao.module.chat.service.chat.ChatPipeline;
import cn.iocoder.yudao.module.chat.service.chat.ChatSendResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 对话")
@RestController
@RequestMapping("/chat/chat")
@Validated
public class ChatController {

    @Resource
    private ChatPipeline chatPipeline;

    @PostMapping("/send")
    @Operation(summary = "发送消息(复用证据平台判定: 可作答带引用回答/不可作答自动转人工)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<ChatSendRespVO> send(@Valid @RequestBody ChatSendReqVO req) {
        ChatSendResult result = chatPipeline.send(req.getConversationId(), req.getMessage(),
                req.getChannel(), req.getCustomerId(), req.getKbId());
        return success(BeanUtils.toBean(result, ChatSendRespVO.class));
    }

}
