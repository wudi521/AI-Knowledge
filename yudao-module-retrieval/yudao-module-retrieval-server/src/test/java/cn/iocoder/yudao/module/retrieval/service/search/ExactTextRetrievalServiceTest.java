package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeContext;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeDecision;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopePipeline;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExactTextRetrievalServiceTest {

    @Mock Bm25Searcher bm25Searcher;
    @Mock ResultFilter resultFilter;
    @Mock RetrievalDomainResolver domainResolver;
    @Mock RetrievalScopePipeline scopePipeline;

    private ExactTextRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new ExactTextRetrievalService(bm25Searcher, resultFilter, domainResolver, scopePipeline);
        when(domainResolver.resolveWithStatus(any(), anyList()))
                .thenAnswer(invocation -> RetrievalDomainResolver.Resolution.success(
                        invocation.getArgument(0) == null ? "GENERAL" : invocation.getArgument(0), false));
        when(scopePipeline.refine(any())).thenAnswer(invocation -> {
            RetrievalScopeContext context = invocation.getArgument(0);
            return new RetrievalScopePipeline.Result(context.documentIds(), false, false, List.of());
        });
    }

    @Test
    void rawExactPathHydratesOnlyVerifiedContentAndSkipsOtherChannels() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithStatus(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(Bm25Searcher.ExactSearchExecution.success(
                        new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 7.2D)), 1L)));
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
        verify(bm25Searcher).searchExactPhraseWithStatus(eq("粒子化磁涌"), eq(1L), eq(List.of(6L)), eq(200), any());
    }

    @Test
    void phraseCandidateWithoutRawSubstringIsRejected() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithStatus(eq("甲乙"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(Bm25Searcher.ExactSearchExecution.success(
                        new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 1D)), 1L)));
        when(resultFilter.filterPublished(Set.of(101L))).thenReturn(Set.of(101L));
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
        when(bm25Searcher.searchExactPhraseWithStatus(eq("测试短语"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(Bm25Searcher.ExactSearchExecution.success(
                        new Bm25Searcher.SearchHits(List.of(Map.entry(101L, 1D)), 201L)));
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

    @Test
    void authoritativeScopeIsAppliedBeforeExactPhraseRecall() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(scopePipeline.refine(any())).thenReturn(new RetrievalScopePipeline.Result(
                List.of(74L), false, false,
                List.of(new RetrievalScopeDecision("patent-scope", List.of(74L), true, false, false, null))));
        when(bm25Searcher.searchExactPhraseWithStatus("磁涌", 1L, List.of(6L), 200, List.of(74L)))
                .thenReturn(Bm25Searcher.ExactSearchExecution.success(Bm25Searcher.SearchHits.empty()));
        RetrievalSearchReqDTO req = request("申请号 202311832214.0 的原文是否包含磁涌？", "磁涌");
        req.setDomainCode("PATENT");

        RetrievalSearchRespDTO resp = service.search(req);

        assertThat(resp.getAnalysis().getBlocked()).isFalse();
        verify(bm25Searcher).searchExactPhraseWithStatus("磁涌", 1L, List.of(6L), 200, List.of(74L));
    }

    @Test
    void elasticsearchFailureMustNotBecomeAuthoritativeZeroHit() {
        when(resultFilter.getVisibleKbIds(9L)).thenReturn(Set.of(6L));
        when(bm25Searcher.searchExactPhraseWithStatus(eq("磁涌"), eq(1L), eq(List.of(6L)), eq(200), any()))
                .thenReturn(Bm25Searcher.ExactSearchExecution.failure("ES unavailable"));

        RetrievalSearchRespDTO resp = service.search(request("原文是否包含磁涌？", "磁涌"));

        assertThat(resp.getResults()).isEmpty();
        assertThat(resp.getAnalysis().getSuccess()).isFalse();
        assertThat(resp.getAnalysis().getDegraded()).isTrue();
        assertThat(resp.getTotalHits()).isNull();
        assertThat(resp.getTotalHitsExact()).isFalse();
        assertThat(resp.getCandidateTotalHits()).isNull();
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
