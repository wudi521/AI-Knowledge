package cn.iocoder.yudao.module.chat.framework.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.service.chat.ChatStreamSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 {@link SseEmitter} 的流式事件输出实现。
 * <p>
 * 事件以 SSE 命名事件输出({@code event: <type> / data: JSON}), 客户端断开/超时/异常时
 * 将 {@link #cancelled} 置位, 供 Pipeline 在阶段边界尽早停止后续 Generate/Verify 与落库。
 */
@Slf4j
public class SseChatStreamSink implements ChatStreamSink {

    private final SseEmitter emitter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public SseChatStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(this::cancel);
        emitter.onTimeout(this::cancel);
        emitter.onError(e -> cancel());
    }

    @Override
    public void emit(ChatStreamEvent event) {
        if (event == null || cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.getType()).data(event));
        } catch (Exception e) {
            // 客户端已断开(写失败): 置取消, Pipeline 收到取消后在阶段边界停止
            log.debug("[SseChatStreamSink][事件输出失败, 标记取消: {}]", e.getMessage());
            cancelled.set(true);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    /** 发送错误事件后关闭(用于幂等拒绝等无法启动任务的场景) */
    public void emitErrorAndComplete(ChatStreamEvent error) {
        try {
            emitter.send(SseEmitter.event().name(error.getType()).data(error));
        } catch (Exception ignored) {
            // 忽略: 客户端可能已断开
        } finally {
            emitter.complete();
        }
    }

    /** 关闭 SSE 连接(幂等) */
    public void complete() {
        emitter.complete();
    }

}
