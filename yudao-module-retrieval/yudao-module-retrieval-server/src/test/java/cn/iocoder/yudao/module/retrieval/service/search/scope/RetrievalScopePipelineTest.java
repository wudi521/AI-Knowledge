package cn.iocoder.yudao.module.retrieval.service.search.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScopePipelineTest {

    @Test
    void domainPluginMayNarrowExistingHardScope() {
        RetrievalScopePipeline pipeline = new RetrievalScopePipeline(List.of(
                plugin("patent", Set.of("PATENT"), List.of(2L), true, false)));

        RetrievalScopePipeline.Result result = pipeline.refine(context("PATENT", List.of(1L, 2L, 3L)));

        assertEquals(List.of(2L), result.documentIds());
        assertFalse(result.blocked());
    }

    @Test
    void pluginCannotBroadenExistingHardScope() {
        RetrievalScopePipeline pipeline = new RetrievalScopePipeline(List.of(
                plugin("bad", Set.of("PATENT"), List.of(2L, 99L), true, false)));

        RetrievalScopePipeline.Result result = pipeline.refine(context("PATENT", List.of(1L, 2L, 3L)));

        assertEquals(List.of(1L, 2L, 3L), result.documentIds());
        assertTrue(result.blocked());
        assertTrue(result.degraded());
    }

    @Test
    void appliedEmptyScopeMustBlockInsteadOfBecomingUnscopedRecall() {
        RetrievalScopePipeline pipeline = new RetrievalScopePipeline(List.of(
                plugin("empty", Set.of("PATENT"), List.of(), true, false)));

        RetrievalScopePipeline.Result result = pipeline.refine(context("PATENT", List.of()));

        assertTrue(result.documentIds().isEmpty());
        assertTrue(result.blocked());
        assertFalse(result.degraded());
    }

    @Test
    void unrelatedDomainPluginLeavesScopeUntouched() {
        RetrievalScopePipeline pipeline = new RetrievalScopePipeline(List.of(
                plugin("patent", Set.of("PATENT"), List.of(2L), true, false)));

        RetrievalScopePipeline.Result result = pipeline.refine(context("CONTRACT", List.of(7L)));

        assertEquals(List.of(7L), result.documentIds());
        assertFalse(result.blocked());
    }

    private RetrievalScopeContext context(String domain, List<Long> documentIds) {
        return new RetrievalScopeContext("q", 1L, List.of(9L), documentIds, domain);
    }

    private RetrievalScopePlugin plugin(String id, Set<String> domains, List<Long> next,
                                        boolean applied, boolean blocked) {
        return new RetrievalScopePlugin() {
            @Override public String pluginId() { return id; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public RetrievalScopeDecision refine(RetrievalScopeContext context) {
                return new RetrievalScopeDecision(id, next, applied, blocked, false, null);
            }
        };
    }
}
