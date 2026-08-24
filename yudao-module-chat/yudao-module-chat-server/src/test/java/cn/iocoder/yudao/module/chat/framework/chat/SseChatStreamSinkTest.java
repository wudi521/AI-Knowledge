package cn.iocoder.yudao.module.chat.framework.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.chat.framework.chat.SseChatStreamSink.State;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SSE-10: SseChatStreamSink 状态机生命周期单测。
 * <p>
 * 覆盖: 正常 complete 恰好一次 / 客户端取消不再二次 complete / 取消后停止发送 /
 * 双重 complete no-op / 取消后 send no-op / timeout 后 complete no-op /
 * transport IOException 标记取消 / 并发 cancel+complete 不抛异常。
 */
@ExtendWith(MockitoExtension.class)
class SseChatStreamSinkTest {

    @Mock
    private SseEmitter emitter;

    private SseChatStreamSink newSink() {
        return new SseChatStreamSink(emitter);
    }

    private ChatStreamEvent delta(String content) {
        return ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_DELTA)
                .content(content)
                .build();
    }

    @Test
    void normalStreamCompletesOnce() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.emit(delta("A"));
        sink.emit(delta("B"));

        sink.complete();

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
        assertThat(sink.getState()).isEqualTo(State.COMPLETED);
    }

    @Test
    void clientCancelDoesNotCompleteAgain() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.cancel();

        // 客户端取消后 complete 必须为 no-op(不得二次 emitter.complete 触发 MimeHeaders NPE)
        sink.complete();

        verify(emitter, never()).complete();
        assertThat(sink.getState()).isEqualTo(State.CLIENT_CANCELLED);
        assertThat(sink.isCancelled()).isTrue();
    }

    @Test
    void cancelWhileGeneratingStopsFurtherEvents() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.emit(delta("A"));
        sink.cancel();

        sink.emit(delta("B"));
        sink.emit(delta("C"));

        // 取消后不再发送任何事件
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void doubleCompleteIsNoOp() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.complete();
        sink.complete();

        verify(emitter, times(1)).complete();
        assertThat(sink.getState()).isEqualTo(State.COMPLETED);
    }

    @Test
    void sendAfterCancelIsNoOp() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.cancel();

        sink.emit(delta("A"));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void timeoutThenCompleteIsNoOp() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.markTimeout();
        // 已 TIMEOUT 后再 complete 必须 no-op
        sink.complete();

        verify(emitter, times(1)).complete(); // 仅 markTimeout 内关闭 transport 一次
        assertThat(sink.getState()).isEqualTo(State.TIMEOUT);
    }

    @Test
    void transportIOExceptionMarksCancelled() throws Exception {
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        SseChatStreamSink sink = newSink();

        // 发送失败不抛出, 内部标记 CLIENT_CANCELLED
        assertThatCode(() -> sink.emit(delta("A"))).doesNotThrowAnyException();

        assertThat(sink.isCancelled()).isTrue();
        assertThat(sink.getState()).isEqualTo(State.CLIENT_CANCELLED);
    }

    @Test
    void simultaneousCancelAndCompleteDoesNotThrow() throws Exception {
        SseChatStreamSink sink = newSink();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> canceller = pool.submit(() -> {
                awaitUnchecked(start);
                for (int i = 0; i < 100; i++) {
                    sink.cancel();
                }
            });
            Future<?> completer = pool.submit(() -> {
                awaitUnchecked(start);
                for (int i = 0; i < 100; i++) {
                    sink.complete();
                }
            });
            start.countDown();
            canceller.get(5, TimeUnit.SECONDS);
            completer.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 并发 cancel/complete: 不抛异常, emitter.complete 至多一次(终态转移只成功一次)
        verify(emitter, atMost(1)).complete();
    }

    @Test
    void emitErrorAndCompleteAfterCancelIsNoOp() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.cancel();

        sink.emitErrorAndComplete(ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_ERROR)
                .code("X")
                .message("x")
                .build());

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
        assertThat(sink.getState()).isEqualTo(State.CLIENT_CANCELLED);
    }

    @Test
    void emitErrorAndCompleteTransitionsToError() throws Exception {
        SseChatStreamSink sink = newSink();
        sink.emitErrorAndComplete(ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_ERROR)
                .code("INTERNAL")
                .message("系统繁忙")
                .retryable(false)
                .build());

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
        assertThat(sink.getState()).isEqualTo(State.ERROR);
    }

    private void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

}
