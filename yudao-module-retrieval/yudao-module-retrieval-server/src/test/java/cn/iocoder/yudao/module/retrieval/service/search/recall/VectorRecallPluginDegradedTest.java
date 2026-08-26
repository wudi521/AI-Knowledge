package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.retrieval.service.search.VectorSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorRecallPluginDegradedTest {

    @Test
    void milvusFailureMustBeExposedAsDegradedRecall() {
        ModelApi modelApi = mock(ModelApi.class);
        when(modelApi.embedding(anyList())).thenReturn(CommonResult.success(List.of(List.of(0.1F, 0.2F))));
        VectorSearcher searcher = mock(VectorSearcher.class);
        when(searcher.searchWithStatus(anyList(), anyLong(), anyList(), anyInt(), any()))
                .thenReturn(VectorSearcher.SearchExecution.failure("Milvus unavailable"));

        RetrievalRecallResult result = new VectorRecallPlugin(searcher, modelApi).recall(
                new RetrievalRecallContext("q", List.of("q"), 1L, List.of(2L), List.of(), 20, "GENERAL"));

        assertTrue(result.degraded());
        assertTrue(result.hits().isEmpty());
    }
}
