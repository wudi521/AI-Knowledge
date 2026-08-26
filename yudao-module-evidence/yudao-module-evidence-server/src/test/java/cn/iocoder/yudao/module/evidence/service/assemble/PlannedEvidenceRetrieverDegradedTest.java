package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannedEvidenceRetrieverDegradedTest {

    @Test
    void degradedEmptyRetrievalIsFailureNotNormalZeroMatch() {
        RetrievalApi api = mock(RetrievalApi.class);
        RetrievalSearchRespDTO data = response(false, true);
        when(api.search(any())).thenReturn(CommonResult.success(data));

        PlannedEvidenceRetriever.Result result = new PlannedEvidenceRetriever(api)
                .search("q", List.of(), List.of(1L), null, 8, 2L, 3L, "t");

        assertTrue(result.failed());
        assertEquals(PlannedEvidenceRetriever.Status.FAILED, result.status());
    }

    @Test
    void healthyEmptyRetrievalRemainsNormalEmpty() {
        RetrievalApi api = mock(RetrievalApi.class);
        RetrievalSearchRespDTO data = response(true, false);
        when(api.search(any())).thenReturn(CommonResult.success(data));

        PlannedEvidenceRetriever.Result result = new PlannedEvidenceRetriever(api)
                .search("q", List.of(), List.of(1L), null, 8, 2L, 3L, "t");

        assertEquals(PlannedEvidenceRetriever.Status.EMPTY, result.status());
    }

    @Test
    void hardScopeBlockedIsDistinctFromEmptyAndFailure() {
        RetrievalApi api = mock(RetrievalApi.class);
        RetrievalSearchRespDTO data = response(true, false);
        data.getAnalysis().setBlocked(true);
        data.getAnalysis().setBlockReason("exact identifier resolved to no document");
        when(api.search(any())).thenReturn(CommonResult.success(data));

        PlannedEvidenceRetriever.Result result = new PlannedEvidenceRetriever(api)
                .search("q", List.of(), List.of(1L), null, 8, 2L, 3L, "PATENT", "t");

        assertEquals(PlannedEvidenceRetriever.Status.BLOCKED, result.status());
        assertTrue(result.blocked());
        assertFalse(result.failed());
        assertEquals("exact identifier resolved to no document", result.errorMessage());
    }

    @Test
    void confirmedDomainIsPropagatedToRetrievalRpc() {
        RetrievalApi api = mock(RetrievalApi.class);
        when(api.search(any())).thenReturn(CommonResult.success(response(true, false)));
        PlannedEvidenceRetriever retriever = new PlannedEvidenceRetriever(api);

        retriever.search("q", List.of("v"), List.of(1L), null, 8, 2L, 3L, "PATENT", "trace");

        ArgumentCaptor<RetrievalSearchReqDTO> captor = ArgumentCaptor.forClass(RetrievalSearchReqDTO.class);
        verify(api).search(captor.capture());
        assertEquals("PATENT", captor.getValue().getDomainCode());
        assertEquals("PLANNED_HYBRID", captor.getValue().getSearchMode());
    }

    @Test
    void exactScopeQueryAndLiteralTextRemainSeparateOnRpcContract() {
        RetrievalApi api = mock(RetrievalApi.class);
        when(api.search(any())).thenReturn(CommonResult.success(response(true, false)));
        PlannedEvidenceRetriever retriever = new PlannedEvidenceRetriever(api);
        String scopeQuery = "申请号 202311832214.0 的原文是否包含磁涌";

        retriever.exactText(scopeQuery, "磁涌", List.of(1L), null, 20,
                2L, 3L, "PATENT", "trace-exact");

        ArgumentCaptor<RetrievalSearchReqDTO> captor = ArgumentCaptor.forClass(RetrievalSearchReqDTO.class);
        verify(api).search(captor.capture());
        RetrievalSearchReqDTO req = captor.getValue();
        assertEquals(scopeQuery, req.getQuery());
        assertEquals("磁涌", req.getExactText());
        assertEquals("PATENT", req.getDomainCode());
        assertEquals("EXACT_TEXT_SEARCH", req.getSearchMode());
    }

    private RetrievalSearchRespDTO response(boolean success, boolean degraded) {
        RetrievalSearchRespDTO data = new RetrievalSearchRespDTO();
        data.setResults(List.of());
        RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
        analysis.setSuccess(success);
        analysis.setDegraded(degraded);
        analysis.setBlocked(false);
        data.setAnalysis(analysis);
        return data;
    }
}
