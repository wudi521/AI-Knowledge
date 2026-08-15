package cn.iocoder.yudao.module.chat.service.chat;

/**
 * 对话 Service 接口
 */
public interface AiChatService {

    /**
     * 发送消息并返回 AI 回复(骨架)
     * TODO 对话工作台: 接入检索(retrieval) + 证据(evidence) + 模型网关(model) 的完整链路,
     *      并支持 SSE 流式输出(org.springframework.web.servlet.mvc.method.annotation.SseEmitter)
     */
    String sendMessage(Long conversationId, String message);

}
