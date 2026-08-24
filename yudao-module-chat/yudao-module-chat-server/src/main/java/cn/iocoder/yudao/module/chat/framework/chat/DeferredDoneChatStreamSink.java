package cn.iocoder.yudao.module.chat.framework.chat;

import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.service.chat.ChatStreamSink;

import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE done 提交屏障。
 *
 * <p>ChatPipeline 在构造最终答案后会先 emit(done)，随后才持久化 ResultSet/ContextFrame/Trace。
 * 如果直接把 done 发给浏览器，前端会立即解锁输入框；用户快速追问时可能读到上一轮尚未提交的上下文。
 * 本 Sink 只缓存 done，其余事件实时透传；待 Pipeline 整体返回后由 Controller 调用 {@link #flushDone()}。
 * 因此对客户端而言：收到 done == 本轮服务端关键状态已经提交完成。</p>
 */
public class DeferredDoneChatStreamSink implements ChatStreamSink {

    private final ChatStreamSink delegate;
    private final AtomicReference<ChatStreamEvent> deferredDone = new AtomicReference<>();

    public DeferredDoneChatStreamSink(ChatStreamSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void emit(ChatStreamEvent event) {
        if (event == null) {
            return;
        }
        if (ChatStreamEvent.TYPE_DONE.equals(event.getType())) {
            // 每个 Query 只允许一个 done；后来的重复 done 忽略。
            deferredDone.compareAndSet(null, event);
            return;
        }
        delegate.emit(event);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    /** Pipeline 全部返回后再真正发送 done。客户端已取消时丢弃缓存，不再写关闭连接。 */
    public void flushDone() {
        if (delegate.isCancelled()) {
            deferredDone.set(null);
            return;
        }
        ChatStreamEvent done = deferredDone.getAndSet(null);
        if (done != null) {
            delegate.emit(done);
        }
    }

    /** 异常路径丢弃尚未发送的 done，避免 done/error 双终态。 */
    public void discardDone() {
        deferredDone.set(null);
    }
}
