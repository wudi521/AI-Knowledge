package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.retrieval.service.search.VectorSearcher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用 Vector Recall 插件。向量模型或 Milvus 不可用时显式标记 degraded，不伪装成权威零命中。 */
@Component
public class VectorRecallPlugin implements RetrievalRecallPlugin {

    private final VectorSearcher searcher;
    private final ModelApi modelApi;

    public VectorRecallPlugin(VectorSearcher searcher, ModelApi modelApi) {
        this.searcher = searcher;
        this.modelApi = modelApi;
    }

    @Override
    public String pluginId() {
        return "retrieval-recall:vector";
    }

    @Override
    public String channel() {
        return "vector";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("*");
    }

    @Override
    public RetrievalRecallResult recall(RetrievalRecallContext context) {
        try {
            List<String> queries = context.variants().isEmpty() ? List.of(context.query()) : context.variants();
            List<List<Float>> vectors = modelApi.embedding(queries).getCheckedData();
            if (vectors == null || vectors.isEmpty()) {
                return new RetrievalRecallResult(pluginId(), channel(), List.of(), true,
                        "embedding returned no vectors", 0L);
            }
            VectorSearcher.SearchExecution execution = searcher.searchWithStatus(
                    vectors, context.tenantId(), context.kbIds(), context.topK(),
                    context.documentIds().isEmpty() ? null : context.documentIds());
            Map<Long, Double> best = new LinkedHashMap<>();
            for (Map.Entry<Long, Double> hit : execution.hits()) best.merge(hit.getKey(), hit.getValue(), Math::min);
            List<Map.Entry<Long, Double>> ranked = best.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .limit(context.topK()).toList();
            return new RetrievalRecallResult(pluginId(), channel(), ranked, execution.failed(),
                    execution.errorMessage(), 0L);
        } catch (Exception e) {
            return new RetrievalRecallResult(pluginId(), channel(), List.of(), true,
                    e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()), 0L);
        }
    }
}
