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
    void rawExactPathHydratesOnlyVerifiedContentAndSkipsOtherChannels() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithTotal(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 7.2D)), 1L));
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

        RetrievalSearchReqDTO req = request("原文中包含“粒子化磁涌”吗？", "粒子化磁涌");
        RetrievalSearchRespDTO resp = service.search(req);

        assertThat(resp.getResults()).hasSize(1);
        assertThat(resp.getTotalHits()).isEqualTo(1L);
        assertThat(resp.getTotalHitsExact()).isTrue();
        assertThat(resp.getCandidateTotalHits()).isEqualTo(1L);
        assertThat(resp.getResults().get(0).getChannels()).containsExactly("exact_text");
        assertThat(resp.getChannels().getVector()).isZero();
        assertThat(resp.getChannels().getFused()).isZero();
        verify(bm25Searcher).searchExactPhraseWithTotal(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(200), any());
    }

    @Test
    void phraseCandidateWithoutRawSubstringIsRejected() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithTotal(eq("甲乙"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 1D)), 1L));
        when(resultFilter.filterPublished(Set.of(101L))).thenReturn(Set.of(101L));
        // 分词短语可能候选命中，但原文并不是连续“甲乙”。
        when(resultFilter.getChunkContents(List.of(101L))).thenReturn(Map.of(101L, "甲，乙"));

        RetrievalSearchRespDTO resp = service.search(request("原文是否包含“甲乙”？", "甲乙"));

        assertThat(resp.getResults()).isEmpty();
        assertThat(resp.getTotalHits()).isZero();
        assertThat(resp.getTotalHitsExact()).isTrue();
        assertThat(resp.getCandidateTotalHits()).isEqualTo(1L);
    }

    @Test
    void oversizedCandidateSetDoesNotPretendExactTotalIsKnown() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithTotal(eq("测试短语"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 1D)), 201L));
        when(resultFilter.filterPublished(Set.of(101L))).thenReturn(Set.of(101L));
        when(resultFilter.getChunkContents(List.of(101L))).thenReturn(Map.of(101L, "测试短语"));
        when(resultFilter.getChunkMetadatas(List.of(101L))).thenReturn(Map.of());
        when(resultFilter.getChunkDocInfo(List.of(101L))).thenReturn(Map.of());

        RetrievalSearchRespDTO resp = service.search(request("哪些地方出现“测试短语”？", "测试短语"));

        assertThat(resp.getResults()).hasSize(1);
        assertThat(resp.getTotalHits()).isNull();
        assertThat(resp.getTotalHitsExact()).isFalse();
        assertThat(resp.getCandidateTotalHits()).isEqualTo(201L);
    }

    private RetrievalSearchReqDTO request(String query, String exactText) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(query);
        req.setExactText(exactText);
        req.setSearchMode("EXACT_TEXT_SEARCH");
        req.setKbIds(List.of(6L));
        req.setTenantId(1L);
        req.setUserId(9L);
        req.setTopK(20);
        return req;
    }
}
