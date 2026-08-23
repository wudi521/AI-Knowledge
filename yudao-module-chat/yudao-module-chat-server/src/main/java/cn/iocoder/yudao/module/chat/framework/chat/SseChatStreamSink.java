package cn.iocoder.yudao.module.chat.framework.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.service.chat.ChatStreamSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 {@link SseEmitter} 的流式事件输出实现。
 * <p>
 * 事件以 SSE 命名事件输出({@code event: <type> / data: JSON})。SSE-01: 以显式状态机管理
 * 连接生命周期(OPEN → COMPLETED / CLIENT_CANCELLED / TIMEOUT / ERROR), 所有终态转移通过
 * CAS 保证只成功一次; SSE-02: {@link #complete()} 仅 OPEN→COMPLETED 成功后才真正
 * {@code emitter.complete()}, 其余终态一律 no-op, 杜绝客户端断开后二次 complete 引发的
 * Tomcat {@code MimeHeaders.setValue} NPE; SSE-05: 非 OPEN 状态不再发送任何事件,
 * 写失败(IOException/IllegalStateException)视为 transport 关闭 → CLIENT_CANCELLED。
 */
@Slf4j
public class SseChatStreamSink implements ChatStreamSink {

    /** SSE-01 流状态机: 所有终态只能成功转移一次 */
    public enum State {
        /** 可发送事件 */
        OPEN,
        /** 服务端正常完成(emit done 后由 Controller complete 收尾) */
        COMPLETED,
        /** 客户端断开/主动停止(前端 AbortController、断连、写失败) */
        CLIENT_CANCELLED,
        /** SSE 连接超时 */
        TIMEOUT,
        /** 服务端异常终止(已发送 error 事件后关闭) */
        ERROR
    }

    private final SseEmitter emitter;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    public SseChatStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(this::cancel);
        emitter.onTimeout(this::markTimeout);
        emitter.onError(e -> cancel());
    }

    @Override
    public void emit(ChatStreamEvent event) {
        if (event == null || state.get() != State.OPEN) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.getType()).data(event));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开/连接已关闭(transport closed): 标记取消, Pipeline 在阶段边界停止后续事件
            log.debug("[SseChatStreamSink][事件输出失败, 标记取消: {}]", e.getMessage());
            state.compareAndSet(State.OPEN, State.CLIENT_CANCELLED);
        } catch (Exception e) {
            // 兜底: 未知发送异常同样按 transport 关闭处理, 不让异常炸掉 chat-stream 线程
            log.debug("[SseChatStreamSink][事件输出异常, 标记取消: {}]", e.getMessage());
            state.compareAndSet(State.OPEN, State.CLIENT_CANCELLED);
        }
    }

    @Override
    public boolean isCancelled() {
        return state.get() == State.CLIENT_CANCELLED;
    }

    /** 是否已进入终态(OPEN 之外) */
    public boolean isTerminal() {
        return state.get() != State.OPEN;
    }

    public State getState() {
        return state.get();
    }

    /** SSE-02: 正常完成——仅 OPEN→COMPLETED CAS 成功才调用 emitter.complete() */
    public void complete() {
        if (!state.compareAndSet(State.OPEN, State.COMPLETED)) {
            log.debug("[SseChatStreamSink][complete 跳过: 当前状态 {}]", state.get());
            return;
        }
        safeCompleteEmitter();
    }

    /** SSE-03: 客户端取消——不调用 emitter.complete()(客户端断开属于正常终态) */
    public void cancel() {
        state.compareAndSet(State.OPEN, State.CLIENT_CANCELLED);
    }

    /** SSE-04: 连接超时——转移为 TIMEOUT 并安全关闭 transport */
    public void markTimeout() {
        if (state.compareAndSet(State.OPEN, State.TIMEOUT)) {
            safeCompleteEmitter();
        }
    }

    /** SSE-04: 服务端异常终止(已发送 error 事件)——转移为 ERROR 并安全关闭 transport */
    public void markError() {
        if (state.compareAndSet(State.OPEN, State.ERROR)) {
            safeCompleteEmitter();
        }
    }

    /** 发送错误事件后关闭(用于幂等拒绝等无法启动任务的场景; 已取消/已终态时为 no-op) */
    public void emitErrorAndComplete(ChatStreamEvent error) {
        if (state.get() == State.OPEN && error != null) {
            try {
                emitter.send(SseEmitter.event().name(error.getType()).data(error));
            } catch (IOException | IllegalStateException ignored) {
                // 忽略: 客户端可能已断开
            } catch (Exception ignored) {
                // 忽略: 不允许错误事件输出失败影响 chat-stream 线程
            }
        }
        markError();
    }

    /** SSE-02: transport cleanup——捕获可能的状态异常, 禁止炸掉 chat-stream executor 线程 */
    private void safeCompleteEmitter() {
        try {
            emitter.complete();
        } catch (RuntimeException e) { // IllegalStateException(连接已关闭)是 RuntimeException 子类
            log.debug("[SseChatStreamSink][emitter.complete 异常(连接可能已关闭): {}]", e.getMessage());
        }
    }

}
