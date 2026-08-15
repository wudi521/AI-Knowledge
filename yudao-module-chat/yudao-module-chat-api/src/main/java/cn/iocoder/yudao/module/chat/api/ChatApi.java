package cn.iocoder.yudao.module.chat.api;

/**
 * 对话工作台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 chat-server
 */
public interface ChatApi {

    /** 占位方法: 按领域替换为真实接口 */
    String sendMessage(Long conversationId, String message);

}
