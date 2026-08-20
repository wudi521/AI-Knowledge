package cn.iocoder.yudao.module.model.service.prompt;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 本地缓存(进程内)
 * <p>
 * TTL 30s: 改/灰度/回滚后 ≤30s 生效, 不依赖显式失效
 */
@Component
public class PromptCache {

    /** TTL 30s(改/灰度/回滚 ≤30s 生效) */
    private static final long TTL_MS = 30_000L;

    /** 缓存条目(全量内容/灰度内容/灰度租户) */
    public record Entry(String enabledContent, String grayContent, List<Long> grayTenantIds, long expireAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public Entry get(String key) {
        Entry e = cache.get(key);
        if (e != null && e.expireAt() > System.currentTimeMillis()) {
            return e;
        }
        if (e != null) {
            cache.remove(key);
        }
        return null;
    }

    public void put(String key, Entry e) {
        cache.put(key, e);
    }

    public void evict(String key) {
        cache.remove(key);
    }

}
