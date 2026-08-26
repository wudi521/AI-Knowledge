package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.rerank.RetrievalRerankPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeDecision;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopePipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void blockedScopeStopsBeforeRecallAndPreservesReason() {
        RetrievalScopePipeline scopePipeline = mock(RetrievalScopePipeline.class);
        RetrievalRecallPipeline recallPipeline = mock(RetrievalRecallPipeline.class);
        RetrievalFusionPipeline fusionPipeline = mock(RetrievalFusionPipeline.class);
        RetrievalRerankPipeline rerankPipeline = mock(RetrievalRerankPipeline.class);
        RetrievalDomainResolver domainResolver = mock(RetrievalDomainResolver.class);
        ResultFilter resultFilter = mock(ResultFilter.class);

        when(resultFilter.getVisibleKbIds(2L)).thenReturn(Set.of(6L));
        when(domainResolver.resolveWithStatus("PATENT", List.of(6L)))
                .thenReturn(RetrievalDomainResolver.Resolution.success("PATENT", false));
        RetrievalScopeDecision decision = new RetrievalScopeDecision(
                "test-scope", List.of(), true, true, false, "hard scope resolved to no document");
        when(scopePipeline.refine(any())).thenReturn(
                new RetrievalScopePipeline.Result(List.of(), true, false, List.of(decision)));

        PlannedSearchService service = new PlannedSearchService(
                scopePipeline, recallPipeline, fusionPipeline, rerankPipeline, domainResolver, resultFilter);
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery("q");
        req.setUserId(2L);
        req.setTenantId(1L);
        req.setKbIds(List.of(6L));
        req.setDomainCode("PATENT");

        RetrievalSearchRespDTO resp = service.search(req);

        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getBlocked()));
        assertEquals("hard scope resolved to no document", resp.getAnalysis().getBlockReason());
        assertFalse(Boolean.TRUE.equals(resp.getAnalysis().getDegraded()));
        assertTrue(Boolean.TRUE.equals(resp.getAnalysis().getSuccess()));
        assertTrue(resp.getResults().isEmpty());
        verifyNoInteractions(recallPipeline, fusionPipeline, rerankPipeline);
    }
}
