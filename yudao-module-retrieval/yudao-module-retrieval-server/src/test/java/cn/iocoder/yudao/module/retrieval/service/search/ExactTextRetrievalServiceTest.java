package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExactTextRetrievalServiceTest {

    @Mock Bm25Searcher bm25Searcher;
    @Mock ResultFilter resultFilter;

    private ExactTextRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new ExactTextRetrievalService(bm25Searcher, resultFilter);
    }

    @Test
    void phraseOnlyPathHydratesPublishedResultsAndSkipsOtherChannels() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhrase(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(20), any()))
                .thenReturn(List.of(Map.entry(101L, 7.2D)));
        when(resultFilter.filterPublished(Set.of(101L))).thenReturn(Set.of(101L));
        when(resultFilter.getChunkContents(List.of(101L))).thenReturn(Map.of(101L, "本发明涉及一种粒子化磁涌装置。"));
        when(resultFilter.getChunkMetadatas(List.of(101L))).thenReturn(Map.of(101L, "{}"));
        ChunkDocInfoDTO info = new ChunkDocInfoDTO();
        info.setChunkId(101L);
        info.setDocumentId(67L);
        info.setDocumentName("一种粒子化磁涌装置及其使用方法");
        info.setVersionNo("V1");
        info.setVersionId(7001L);
        when(resultFilter.getChunkDocInfo(List.of(101L))).thenReturn(Map.of(101L, info));

        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery("原文中包含“粒子化磁涌”吗？");
        req.setExactText("粒子化磁涌");
        req.setSearchMode("EXACT_TEXT_SEARCH");
        req.setKbIds(List.of(6L));
        req.setTenantId(1L);
        req.setUserId(9L);

        RetrievalSearchRespDTO resp = service.search(req);

        assertThat(resp.getResults()).hasSize(1);
        assertThat(resp.getResults().get(0).getChannels()).containsExactly("exact_text");
        assertThat(resp.getChannels().getBm25()).isEqualTo(1);
        assertThat(resp.getChannels().getVector()).isZero();
        assertThat(resp.getChannels().getFused()).isZero();
        assertThat(resp.getAnalysis().getRoute()).isEqualTo("HYBRID_RAG");
        verify(bm25Searcher).searchExactPhrase(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(20), any());
    }
}
