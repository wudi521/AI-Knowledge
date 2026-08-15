package cn.iocoder.yudao.module.chat.api;

import cn.iocoder.yudao.module.chat.api.ChatApi;
import org.springframework.stereotype.Service;

/**
 * 对话工作台 对外 RPC 实现
 */
@Service
public class ChatApiImpl implements ChatApi {

    @Override
    public String sendMessage(Long conversationId, String message) {
    return "";

}
