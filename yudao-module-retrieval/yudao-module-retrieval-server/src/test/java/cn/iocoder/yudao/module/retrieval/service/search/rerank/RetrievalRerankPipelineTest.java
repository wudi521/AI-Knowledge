package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalRerankPipelineTest {

    @Test
    void exactDomainRerankerWinsOverGenericFallback() {
        RetrievalRerankPipeline pipeline = new RetrievalRerankPipeline(List.of(
                plugin("generic", Set.of("*"), 0),
                plugin("patent", Set.of("PATENT"), 1)));

        RetrievalRerankResult result = pipeline.rerank(new RetrievalRerankContext("q", List.of("a", "b"), "PATENT"));

        assertEquals("patent", result.pluginId());
        assertEquals(1, result.rankings().get(0).getKey());
    }

    @Test
    void unknownDomainUsesGenericReranker() {
        RetrievalRerankPipeline pipeline = new RetrievalRerankPipeline(List.of(
                plugin("generic", Set.of("*"), 0),
                plugin("patent", Set.of("PATENT"), 1)));

        assertEquals("generic", pipeline.rerank(new RetrievalRerankContext("q", List.of("a"), "CONTRACT")).pluginId());
    }

    private RetrievalRerankPlugin plugin(String id, Set<String> domains, int firstIndex) {
        return new RetrievalRerankPlugin() {
            @Override public String pluginId() { return id; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public RetrievalRerankResult rerank(RetrievalRerankContext context) {
                return new RetrievalRerankResult(id, List.of(Map.entry(firstIndex, 1F)), false, null, 0L);
            }
        };
    }
}
