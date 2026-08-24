package cn.iocoder.yudao.module.chat.framework.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.service.chat.ChatStreamSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredDoneChatStreamSinkTest {

    @Test
    void doneIsInvisibleUntilFlushAndOnlyEmittedOnce() {
        RecordingSink delegate = new RecordingSink();
        DeferredDoneChatStreamSink sink = new DeferredDoneChatStreamSink(delegate);

        sink.emit(ChatStreamEvent.builder().type(ChatStreamEvent.TYPE_DELTA).content("A").build());
        sink.emit(ChatStreamEvent.builder().type(ChatStreamEvent.TYPE_DONE).conversationId(200L).build());
        sink.emit(ChatStreamEvent.builder().type(ChatStreamEvent.TYPE_DONE).conversationId(201L).build());

        assertThat(delegate.events).hasSize(1);
        assertThat(delegate.events.get(0).getType()).isEqualTo(ChatStreamEvent.TYPE_DELTA);

        sink.flushDone();
        sink.flushDone();

        assertThat(delegate.events).hasSize(2);
        assertThat(delegate.events.get(1).getType()).isEqualTo(ChatStreamEvent.TYPE_DONE);
        assertThat(delegate.events.get(1).getConversationId()).isEqualTo(200L);
    }

    @Test
    void cancelledConnectionDropsDeferredDone() {
        RecordingSink delegate = new RecordingSink();
        DeferredDoneChatStreamSink sink = new DeferredDoneChatStreamSink(delegate);

        sink.emit(ChatStreamEvent.builder().type(ChatStreamEvent.TYPE_DONE).conversationId(200L).build());
        delegate.cancelled = true;
        sink.flushDone();

        assertThat(delegate.events).isEmpty();
    }

    private static final class RecordingSink implements ChatStreamSink {
        private final List<ChatStreamEvent> events = new ArrayList<>();
        private boolean cancelled;

        @Override
        public void emit(ChatStreamEvent event) {
            events.add(event);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
