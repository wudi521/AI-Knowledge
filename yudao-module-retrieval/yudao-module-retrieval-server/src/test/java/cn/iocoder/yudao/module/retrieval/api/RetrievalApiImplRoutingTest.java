package cn.iocoder.yudao.module.retrieval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchMode;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import cn.iocoder.yudao.module.retrieval.service.search.ExactTextRetrievalService;
import cn.iocoder.yudao.module.retrieval.service.search.PlannedSearchService;
import cn.iocoder.yudao.module.retrieval.service.search.SearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalApiImplRoutingTest {

    @Test
    void plannedModeNeverFallsIntoLegacySearchService() {
        SearchService legacy = mock(SearchService.class);
        ExactTextRetrievalService exact = mock(ExactTextRetrievalService.class);
        PlannedSearchService planned = mock(PlannedSearchService.class);
        RetrievalApiImpl api = new RetrievalApiImpl(legacy, exact, planned);
        RetrievalSearchReqDTO req = request(RetrievalSearchMode.PLANNED_HYBRID.code());
        RetrievalSearchRespDTO plannedResp = new RetrievalSearchRespDTO();
        plannedResp.setResults(List.of());
        when(planned.search(req)).thenReturn(plannedResp);

        CommonResult<RetrievalSearchRespDTO> result = api.search(req);

        assertTrue(result.isSuccess());
        verify(planned).search(req);
        verifyNoInteractions(legacy, exact);
    }

    @Test
    void exactModeNeverFallsIntoLegacyOrPlannedPipeline() {
        SearchService legacy = mock(SearchService.class);
        ExactTextRetrievalService exact = mock(ExactTextRetrievalService.class);
        PlannedSearchService planned = mock(PlannedSearchService.class);
        RetrievalApiImpl api = new RetrievalApiImpl(legacy, exact, planned);
        RetrievalSearchReqDTO req = request(RetrievalSearchMode.EXACT_TEXT_SEARCH.code());
        RetrievalSearchRespDTO exactResp = new RetrievalSearchRespDTO();
        exactResp.setResults(List.of());
        when(exact.search(req)).thenReturn(exactResp);

        CommonResult<RetrievalSearchRespDTO> result = api.search(req);

        assertTrue(result.isSuccess());
        verify(exact).search(req);
        verifyNoInteractions(legacy, planned);
    }

    @Test
    void unknownExplicitModeFailsClosedInsteadOfUsingLegacy() {
        SearchService legacy = mock(SearchService.class);
        ExactTextRetrievalService exact = mock(ExactTextRetrievalService.class);
        PlannedSearchService planned = mock(PlannedSearchService.class);
        RetrievalApiImpl api = new RetrievalApiImpl(legacy, exact, planned);

        CommonResult<RetrievalSearchRespDTO> result = api.search(request("PLANNED_HYBIRD"));

        assertEquals(400, result.getCode());
        verifyNoInteractions(legacy, exact, planned);
    }

    @Test
    void blankModeIsTheOnlyLegacyCompatibilitySignal() {
        SearchService legacy = mock(SearchService.class);
        ExactTextRetrievalService exact = mock(ExactTextRetrievalService.class);
        PlannedSearchService planned = mock(PlannedSearchService.class);
        RetrievalApiImpl api = new RetrievalApiImpl(legacy, exact, planned);
        RetrievalSearchReqDTO req = request(null);
        RetrievalRespVO legacyResp = new RetrievalRespVO();
        legacyResp.setResults(List.of());
        when(legacy.search("q", List.of(6L), 8, 1L, 2L, List.of(), "trace", List.of()))
                .thenReturn(legacyResp);

        CommonResult<RetrievalSearchRespDTO> result = api.search(req);

        assertTrue(result.isSuccess());
        verify(legacy).search("q", List.of(6L), 8, 1L, 2L, List.of(), "trace", List.of());
        verifyNoInteractions(exact, planned);
    }

    private RetrievalSearchReqDTO request(String mode) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery("q");
        req.setKbIds(List.of(6L));
        req.setTopK(8);
        req.setTenantId(1L);
        req.setUserId(2L);
        req.setHistory(List.of());
        req.setTraceId("trace");
        req.setDocumentIds(List.of());
        req.setSearchMode(mode);
        return req;
    }
}
