package cn.iocoder.yudao.module.chat.service.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;

/**
 * 流式事件输出通道(ChatPipeline → SSE / 测试收集器)
 * <p>
 * Pipeline 通过 {@link #emit} 输出真实执行过程事件; 客户端断开后应通过 {@link #isCancelled()}
 * 让 Pipeline 在阶段边界尽早停止后续 Generate/Verify 与落库(尽力而为, 不中断正在进行的 RPC)。
 */
public interface ChatStreamSink {

    /**
     * 输出一个事件(底层可能抛 IOException, 调用方按取消处理)
     */
    void emit(ChatStreamEvent event);

    /**
     * 客户端是否已断开(前端 AbortController / SSE 连接中断)
     */
    boolean isCancelled();

}
