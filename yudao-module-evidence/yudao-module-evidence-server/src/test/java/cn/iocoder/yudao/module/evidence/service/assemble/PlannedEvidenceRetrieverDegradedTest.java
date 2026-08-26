package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    private RetrievalSearchRespDTO response(boolean success, boolean degraded) {
        RetrievalSearchRespDTO data = new RetrievalSearchRespDTO();
        data.setResults(List.of());
        RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
        analysis.setSuccess(success);
        analysis.setDegraded(degraded);
        data.setAnalysis(analysis);
        return data;
    }
}
