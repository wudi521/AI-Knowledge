package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalFusionPipelineTest {

    @Test
    void exactDomainFusionWinsOverGenericFallback() {
        RetrievalFusionPipeline pipeline = new RetrievalFusionPipeline(List.of(
                plugin("generic", Set.of("*"), 1L),
                plugin("patent", Set.of("PATENT"), 2L)));

        RetrievalFusionResult result = pipeline.fuse(new RetrievalFusionContext("PATENT", List.of(), 20));

        assertEquals("patent", result.pluginId());
        assertEquals(List.of(2L), result.hits().stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void unknownDomainUsesGenericFusion() {
        RetrievalFusionPipeline pipeline = new RetrievalFusionPipeline(List.of(
                plugin("generic", Set.of("*"), 1L),
                plugin("patent", Set.of("PATENT"), 2L)));

        assertEquals("generic", pipeline.fuse(new RetrievalFusionContext("CONTRACT", List.of(), 20)).pluginId());
    }

    private RetrievalFusionPlugin plugin(String id, Set<String> domains, long chunkId) {
        return new RetrievalFusionPlugin() {
            @Override public String pluginId() { return id; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public RetrievalFusionResult fuse(RetrievalFusionContext context) {
                return new RetrievalFusionResult(id, List.of(Map.entry(chunkId, 1D)), false, null, 0L);
            }
        };
    }
}
