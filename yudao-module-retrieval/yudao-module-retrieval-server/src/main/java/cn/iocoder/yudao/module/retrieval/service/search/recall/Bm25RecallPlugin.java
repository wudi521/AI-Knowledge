package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.module.retrieval.service.search.Bm25Searcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** 通用 BM25 Recall 插件。 */
@Component
public class Bm25RecallPlugin implements RetrievalRecallPlugin {

    private final Bm25Searcher searcher;

    public Bm25RecallPlugin(Bm25Searcher searcher) {
        this.searcher = searcher;
    }

    @Override
    public String pluginId() {
        return "retrieval-recall:bm25";
    }

    @Override
    public String channel() {
        return "bm25";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("*");
    }

    @Override
    public RetrievalRecallResult recall(RetrievalRecallContext context) {
        List<Map.Entry<Long, Double>> all = new ArrayList<>();
        boolean degraded = false;
        StringJoiner errors = new StringJoiner(" | ");
        List<String> queries = context.variants().isEmpty() ? List.of(context.query()) : context.variants();
        for (String variant : queries) {
            Bm25Searcher.SearchExecution execution = searcher.searchWithStatus(
                    variant, context.tenantId(), context.kbIds(), context.topK(),
                    context.documentIds().isEmpty() ? null : context.documentIds());
            all.addAll(execution.hits());
            if (execution.failed()) {
                degraded = true;
                errors.add(execution.errorMessage() == null ? "BM25 execution failed" : execution.errorMessage());
            }
        }
        Map<Long, Double> best = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> hit : all) best.merge(hit.getKey(), hit.getValue(), Math::max);
        List<Map.Entry<Long, Double>> ranked = best.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(context.topK()).toList();
        return new RetrievalRecallResult(pluginId(), channel(), ranked, degraded,
                errors.length() == 0 ? null : errors.toString(), 0L);
    }
}
