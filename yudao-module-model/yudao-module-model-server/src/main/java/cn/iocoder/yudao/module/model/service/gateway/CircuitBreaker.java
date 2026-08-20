package cn.iocoder.yudao.module.model.service.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内熔断器: 单模型连续失败≥5 → OPEN 30s(窗口内跳过); 窗口过后半开放(放行探测), 成功恢复
 * key 用 "type:modelId"(yaml 兜底不熔断)
 */
@Component
public class CircuitBreaker {

    /** 连续失败阈值 */
    private static final int FAILURE_THRESHOLD = 5;
    /** 短路窗口 ms */
    private static final long OPEN_WINDOW_MS = 30_000L;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private record State(int failures, long openedAt) {
    }

    /** 是否允许发起请求 */
    public boolean tryAcquire(String key) {
        State s = states.get(key);
        if (s == null || s.failures() < FAILURE_THRESHOLD) {
            return true;
        }
        // 达到阈值: 短路窗口内拒绝; 窗口过后半开(放行探测)
        return System.currentTimeMillis() - s.openedAt() > OPEN_WINDOW_MS;
    }

    public void onSuccess(String key) {
        states.remove(key);
    }

    public void onFailure(String key) {
        State s = states.get(key);
        int failures = (s == null ? 0 : s.failures()) + 1;
        long openedAt = failures >= FAILURE_THRESHOLD ? System.currentTimeMillis() : (s == null ? 0 : s.openedAt());
        states.put(key, new State(failures, openedAt));
    }
}
