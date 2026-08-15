package cn.iocoder.yudao.module.chat.service.chat;

import org.springframework.stereotype.Service;

/**
 * 对话 Service 实现(骨架)
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    @Override
    public String sendMessage(Long conversationId, String message) {
        // TODO 接入真实链路: retrieval → evidence → model, 并落库 ai_message
        return "AI 回复(骨架): 已收到问题[" + message + "], 接入检索链路后返回真实答案";
    }

}
