package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import cn.iocoder.yudao.module.retrieval.service.search.RrfMerger;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 默认通用 RRF Fusion 插件。 */
@Component
public class RrfFusionPlugin implements RetrievalFusionPlugin {

    private final RrfMerger merger;

    public RrfFusionPlugin(RrfMerger merger) {
        this.merger = merger;
    }

    @Override
    public String pluginId() {
        return "retrieval-fusion:rrf";
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("*");
    }

    @Override
    public RetrievalFusionResult fuse(RetrievalFusionContext context) {
        List<List<Map.Entry<Long, Double>>> ranked = context.recalls().stream()
                .map(RetrievalRecallResult::hits).toList();
        return new RetrievalFusionResult(pluginId(), merger.merge(ranked, context.topK()), false, null, 0L);
    }
}
