package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import java.util.List;
import java.util.Map;

/** 单个 Rerank 插件的标准输出，key 为候选下标。 */
public record RetrievalRerankResult(String pluginId,
                                    List<Map.Entry<Integer, Float>> rankings,
                                    boolean degraded,
                                    String message,
                                    long elapsedMs) {
    public RetrievalRerankResult {
        rankings = rankings == null ? List.of() : List.copyOf(rankings);
        elapsedMs = Math.max(0L, elapsedMs);
    }
}
