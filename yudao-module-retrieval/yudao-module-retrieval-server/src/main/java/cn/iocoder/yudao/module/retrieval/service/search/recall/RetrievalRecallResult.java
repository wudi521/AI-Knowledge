package cn.iocoder.yudao.module.retrieval.service.search.recall;

import java.util.List;
import java.util.Map;

/** 单个 Recall 插件的标准输出。hits 的顺序就是该通道提供给 RRF 的 rank。 */
public record RetrievalRecallResult(String pluginId,
                                    String channel,
                                    List<Map.Entry<Long, Double>> hits,
                                    boolean degraded,
                                    String message,
                                    long elapsedMs) {
    public RetrievalRecallResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        channel = channel == null ? "unknown" : channel;
        pluginId = pluginId == null ? channel : pluginId;
        elapsedMs = Math.max(0L, elapsedMs);
    }
}
