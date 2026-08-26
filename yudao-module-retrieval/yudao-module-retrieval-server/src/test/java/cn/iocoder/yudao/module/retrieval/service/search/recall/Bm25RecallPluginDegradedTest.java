package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.module.retrieval.service.search.Bm25Searcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Bm25RecallPluginDegradedTest {

    @Test
    void searcherFailureMustBeExposedAsDegradedRecall() {
        Bm25Searcher searcher = mock(Bm25Searcher.class);
        when(searcher.searchWithStatus(anyString(), anyLong(), anyList(), anyInt(), any()))
                .thenReturn(Bm25Searcher.SearchExecution.failure("ES unavailable"));

        RetrievalRecallResult result = new Bm25RecallPlugin(searcher).recall(
                new RetrievalRecallContext("q", List.of("q"), 1L, List.of(2L), List.of(), 20, "GENERAL"));

        assertTrue(result.degraded());
        assertTrue(result.hits().isEmpty());
    }
}
