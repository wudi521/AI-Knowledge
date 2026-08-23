package cn.iocoder.yudao.module.chat.framework.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流式问答执行器(守护线程池): 承载 {@code /chat/stream} 的 SSE 异步任务。
 * <p>
 * 每个任务在线程上恢复 SecurityContext + TenantContext(见 ChatController), 结束后清理;
 * 线程为 daemon, 应用退出不阻塞。
 */
@Slf4j
@Component
public class ChatStreamExecutor {

    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "chat-stream");
        t.setDaemon(true);
        return t;
    });

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public void shutdown() {
        executor.shutdown();
    }

}
