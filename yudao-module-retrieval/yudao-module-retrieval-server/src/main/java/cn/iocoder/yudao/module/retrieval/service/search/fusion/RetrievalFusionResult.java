package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import java.util.List;
import java.util.Map;

/** 单个 Fusion 插件的标准输出。 */
public record RetrievalFusionResult(String pluginId,
                                    List<Map.Entry<Long, Double>> hits,
                                    boolean degraded,
                                    String message,
                                    long elapsedMs) {
    public RetrievalFusionResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        elapsedMs = Math.max(0L, elapsedMs);
    }
}
