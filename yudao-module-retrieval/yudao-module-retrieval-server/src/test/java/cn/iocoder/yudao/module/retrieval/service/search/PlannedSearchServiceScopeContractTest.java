package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionResult;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallResult;
import cn.iocoder.yudao.module.retrieval.service.search.rerank.RetrievalRerankPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeDecision;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopePipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannedSearchServiceScopeContractTest {

    @Test
    void visibilitySourceFailureIsExecutionFailureNotPermissionEmpty() {
        Fixture f = fixture();
        when(f.resultFilter.getVisibleKbIdsResult(2L))
                .thenReturn(ResultFilter.ReadResult.failure(Set.of(), "knowledge service down"));

        RetrievalSearchRespDTO resp = f.service.search(request(List.of(6L), "PATENT"));

        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getBlocked()));
        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getDegraded()));
        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getSuccess()));
        assertTrue(resp.getResults().isEmpty());
        verifyNoInteractions(f.domainResolver, f.scopePipeline, f.recallPipeline, f.fusionPipeline, f.rerankPipeline);
    }

    @Test
    void blockedScopeStopsBeforeRecallAndPreservesReason() {
        Fixture f = fixture();
        when(f.resultFilter.getVisibleKbIdsResult(2L)).thenReturn(ResultFilter.ReadResult.success(Set.of(6L)));
        when(f.domainResolver.resolveWithStatus("PATENT", List.of(6L)))
                .thenReturn(RetrievalDomainResolver.Resolution.success("PATENT", false));
        RetrievalScopeDecision decision = new RetrievalScopeDecision(
                "test-scope", List.of(), true, true, false, "hard scope resolved to no document");
        when(f.scopePipeline.refine(any())).thenReturn(
                new RetrievalScopePipeline.Result(List.of(), true, false, List.of(decision)));

        RetrievalSearchRespDTO resp = f.service.search(request(List.of(6L), "PATENT"));

        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getBlocked()));
        assertEquals("hard scope resolved to no document", resp.getAnalysis().getBlockReason());
        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getDegraded()));
        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getSuccess()));
        assertTrue(resp.getResults().isEmpty());
        verifyNoInteractions(f.recallPipeline, f.fusionPipeline, f.rerankPipeline);
    }

    @Test
    void mixedDomainScopeMustBePartitionedBeforeAnyDomainPluginRuns() {
        Fixture f = fixture();
        when(f.resultFilter.getVisibleKbIdsResult(2L)).thenReturn(ResultFilter.ReadResult.success(Set.of(6L, 7L)));
        when(f.domainResolver.resolveWithStatus(null, List.of(6L, 7L)))
                .thenReturn(RetrievalDomainResolver.Resolution.success("GENERAL", true));

        RetrievalSearchRespDTO resp = f.service.search(request(List.of(6L, 7L), null));

        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getBlocked()));
        assertEquals("mixed-domain knowledge scope must be partitioned by domain before retrieval",
                resp.getAnalysis().getBlockReason());
        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getDegraded()));
        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getSuccess()));
        verifyNoInteractions(f.scopePipeline, f.recallPipeline, f.fusionPipeline, f.rerankPipeline);
    }

    @Test
    void contentHydrationFailureIsNotNormalNoMatch() {
        Fixture f = fixture();
        when(f.resultFilter.getVisibleKbIdsResult(2L)).thenReturn(ResultFilter.ReadResult.success(Set.of(6L)));
        when(f.domainResolver.resolveWithStatus("PATENT", List.of(6L)))
                .thenReturn(RetrievalDomainResolver.Resolution.success("PATENT", false));
        when(f.scopePipeline.refine(any())).thenReturn(
                new RetrievalScopePipeline.Result(List.of(), false, false, List.of()));
        when(f.recallPipeline.recall(any())).thenReturn(List.of(
                new RetrievalRecallResult("recall", "bm25", List.of(Map.entry(101L, 1D)), false, null, 1L)));
        when(f.fusionPipeline.fuse(any())).thenReturn(
                new RetrievalFusionResult("fusion", List.of(Map.entry(101L, 1D)), false, null, 1L));
        when(f.resultFilter.filterPublishedResult(Set.of(101L)))
                .thenReturn(ResultFilter.ReadResult.success(Set.of(101L)));
        when(f.resultFilter.getChunkContentsResult(List.of(101L)))
                .thenReturn(ResultFilter.ReadResult.failure(Map.of(), "ingestion unavailable"));
        when(f.resultFilter.getChunkDocInfoResult(List.of(101L)))
                .thenReturn(ResultFilter.ReadResult.success(Map.of()));
        when(f.resultFilter.getChunkMetadatasResult(List.of(101L)))
                .thenReturn(ResultFilter.ReadResult.success(Map.of()));

        RetrievalSearchRespDTO resp = f.service.search(request(List.of(6L), "PATENT"));

        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getBlocked()));
        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getDegraded()));
        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getSuccess()));
        assertTrue(resp.getResults().isEmpty());
        verifyNoInteractions(f.rerankPipeline);
    }

    private Fixture fixture() {
        RetrievalScopePipeline scopePipeline = mock(RetrievalScopePipeline.class);
        RetrievalRecallPipeline recallPipeline = mock(RetrievalRecallPipeline.class);
        RetrievalFusionPipeline fusionPipeline = mock(RetrievalFusionPipeline.class);
        RetrievalRerankPipeline rerankPipeline = mock(RetrievalRerankPipeline.class);
        RetrievalDomainResolver domainResolver = mock(RetrievalDomainResolver.class);
        ResultFilter resultFilter = mock(ResultFilter.class);
        PlannedSearchService service = new PlannedSearchService(
                scopePipeline, recallPipeline, fusionPipeline, rerankPipeline, domainResolver, resultFilter);
        return new Fixture(service, scopePipeline, recallPipeline, fusionPipeline, rerankPipeline, domainResolver, resultFilter);
    }

    private RetrievalSearchReqDTO request(List<Long> kbIds, String domainCode) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery("q");
        req.setUserId(2L);
        req.setTenantId(1L);
        req.setKbIds(kbIds);
        req.setDomainCode(domainCode);
        return req;
    }

    private record Fixture(PlannedSearchService service,
                           RetrievalScopePipeline scopePipeline,
                           RetrievalRecallPipeline recallPipeline,
                           RetrievalFusionPipeline fusionPipeline,
                           RetrievalRerankPipeline rerankPipeline,
                           RetrievalDomainResolver domainResolver,
                           ResultFilter resultFilter) {
    }
}
