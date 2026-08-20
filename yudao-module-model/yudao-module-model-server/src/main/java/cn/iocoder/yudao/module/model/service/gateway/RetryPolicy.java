package cn.iocoder.yudao.module.model.service.gateway;

import org.springframework.stereotype.Component;

/**
 * 重试策略: 瞬态错误(超时/5xx/网络)重试 1 次(共 2 次尝试), 线性退避 200/400ms; 4xx 不重试
 */
@Component
public class RetryPolicy {

    /** 瞬态错误最大尝试次数 */
    public static final int MAX_ATTEMPTS = 2;

    /** 退避基数 ms */
    private static final long BACKOFF_BASE_MS = 200L;

    /** 可重试场景的最大尝试次数; 4xx 场景仅 1 次 */
    public int attempts(boolean retryable) {
        return retryable ? MAX_ATTEMPTS : 1;
    }

    /** 第 attempt 次失败后的退避(attempt 从 1 起; 退避发生在重试前) */
    public void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
