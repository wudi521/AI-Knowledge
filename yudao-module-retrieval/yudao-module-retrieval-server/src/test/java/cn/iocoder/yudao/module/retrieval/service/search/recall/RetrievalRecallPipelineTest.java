package cn.iocoder.yudao.module.retrieval.service.search.recall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalRecallPipelineTest {

    @Test
    void exactDomainRecallRunsBeforeGenericRecall() {
        RetrievalRecallPipeline pipeline = new RetrievalRecallPipeline(List.of(
                plugin("generic", "bm25", Set.of("*"), 10L),
                plugin("patent-id", "patent-id", Set.of("PATENT"), 20L)));

        List<RetrievalRecallResult> results = pipeline.recall(context("PATENT"));

        assertEquals(List.of("patent-id", "generic"), results.stream().map(RetrievalRecallResult::pluginId).toList());
        assertEquals(List.of("patent-id", "bm25"), results.stream().map(RetrievalRecallResult::channel).toList());
    }

    @Test
    void unknownDomainStillRunsGenericRecallWithoutCoreBranch() {
        RetrievalRecallPipeline pipeline = new RetrievalRecallPipeline(List.of(
                plugin("generic", "bm25", Set.of("*"), 10L),
                plugin("patent-id", "patent-id", Set.of("PATENT"), 20L)));

        assertEquals(List.of("generic"), pipeline.recall(context("CONTRACT"))
                .stream().map(RetrievalRecallResult::pluginId).toList());
    }

    private RetrievalRecallContext context(String domain) {
        return new RetrievalRecallContext("q", List.of("q"), 1L, List.of(2L), List.of(), 20, domain);
    }

    private RetrievalRecallPlugin plugin(String id, String channel, Set<String> domains, long chunkId) {
        return new RetrievalRecallPlugin() {
            @Override public String pluginId() { return id; }
            @Override public String channel() { return channel; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public RetrievalRecallResult recall(RetrievalRecallContext context) {
                return new RetrievalRecallResult(id, channel, List.of(Map.entry(chunkId, 1D)), false, null, 0L);
            }
        };
    }
}
