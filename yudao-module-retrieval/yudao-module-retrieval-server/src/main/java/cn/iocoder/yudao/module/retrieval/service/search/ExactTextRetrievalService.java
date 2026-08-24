package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EXACT_TEXT_SEARCH 快路径。
 * ES match_phrase 仅做候选召回，随后必须对原文 content.contains(exactText) 做逐字二次校验。
 */
@Slf4j
@Service
public class ExactTextRetrievalService {

    /** 最终返回给上层的证据上限。 */
    private static final int MAX_RETURN = 20;
    /** 单次用于逐字二次校验的 phrase 候选上限；超过后不得宣称 exact 全集完整。 */
    private static final int MAX_EXACT_CANDIDATES = 200;

    private final Bm25Searcher bm25Searcher;
    private final ResultFilter resultFilter;

    public ExactTextRetrievalService(Bm25Searcher bm25Searcher, ResultFilter resultFilter) {
        this.bm25Searcher = bm25Searcher;
        this.resultFilter = resultFilter;
    }

    public RetrievalSearchRespDTO search(RetrievalSearchReqDTO req) {
        long start = System.currentTimeMillis();
        RetrievalSearchRespDTO resp = base(req);
        if (req == null || StrUtil.isBlank(req.getExactText())) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("精确原文检索缺少目标短语");
            resp.setResults(List.of());
            resp.setTotalHits(0L);
            resp.setTotalHitsExact(true);
            resp.setCandidateTotalHits(0L);
            attachStages(resp, System.currentTimeMillis() - start, 0, false);
            return resp;
        }

        Set<Long> visible = resultFilter.getVisibleKbIds(req.getUserId());
        List<Long> kbIds = req.getKbIds() != null && !req.getKbIds().isEmpty()
                ? req.getKbIds().stream().filter(visible::contains).distinct().toList()
                : new ArrayList<>(visible);
        if (kbIds.isEmpty()) {
            resp.setResults(List.of());
            resp.setTotalHits(0L);
            resp.setTotalHitsExact(true);
            resp.setCandidateTotalHits(0L);
            attachStages(resp, System.currentTimeMillis() - start, 0, false);
            return resp;
        }

        int returnLimit = req.getTopK() == null || req.getTopK() <= 0 ? 10 : Math.min(req.getTopK(), MAX_RETURN);
        long bm25Start = System.currentTimeMillis();
        Bm25Searcher.SearchHits searchHits = bm25Searcher.searchExactPhraseWithTotal(
                req.getExactText(), req.getTenantId(), kbIds, MAX_EXACT_CANDIDATES, req.getDocumentIds());
        long bm25Ms = System.currentTimeMillis() - bm25Start;
        resp.setCandidateTotalHits(searchHits.totalHits());

        List<Map.Entry<Long, Double>> hits = searchHits.hits();
        List<Long> orderedIds = hits.stream().map(Map.Entry::getKey).distinct().toList();
        boolean allCandidatesInspected = searchHits.totalHits() <= MAX_EXACT_CANDIDATES;
        if (orderedIds.isEmpty()) {
            resp.setResults(List.of());
            resp.setTotalHits(allCandidatesInspected ? 0L : null);
            resp.setTotalHitsExact(allCandidatesInspected);
            resp.getChannels().setBm25(0);
            attachStages(resp, System.currentTimeMillis() - start, bm25Ms, true);
            return resp;
        }

        Set<Long> published = resultFilter.filterPublished(new HashSet<>(orderedIds));
        List<Long> candidateIds = orderedIds.stream().filter(published::contains).toList();
        Map<Long, String> contents = resultFilter.getChunkContents(candidateIds);

        // 第二道“原文逐字”门禁：match_phrase 只是召回候选，不直接算 exact hit。
        List<Long> exactIds = candidateIds.stream()
                .filter(id -> {
                    String content = contents.get(id);
                    return content != null && content.contains(req.getExactText());
                })
                .toList();

        if (allCandidatesInspected) {
            resp.setTotalHits((long) exactIds.size());
            resp.setTotalHitsExact(true);
        } else {
            resp.setTotalHits(null);
            resp.setTotalHitsExact(false);
        }

        List<Long> returnIds = exactIds.stream().limit(returnLimit).toList();
        Map<Long, String> metadata = resultFilter.getChunkMetadatas(returnIds);
        Map<Long, ChunkDocInfoDTO> docInfo = resultFilter.getChunkDocInfo(returnIds);
        Map<Long, Double> scores = hits.stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, Math::max));

        List<RetrievalResultDTO> results = new ArrayList<>();
        for (Long id : returnIds) {
            RetrievalResultDTO item = new RetrievalResultDTO();
            item.setChunkId(id);
            item.setContent(contents.get(id));
            ChunkDocInfoDTO info = docInfo.get(id);
            if (info != null) {
                item.setDocumentId(info.getDocumentId());
                item.setDocumentName(info.getDocumentName());
                item.setVersionNo(info.getVersionNo());
                item.setVersionId(info.getVersionId());
            }
            item.setRrfScore(scores.getOrDefault(id, 1D));
            item.setRerankScore(null);
            item.setChannels(List.of("exact_text"));
            item.setChunkMetadata(metadata.get(id));
            results.add(item);
        }
        resp.setResults(results);
        resp.getChannels().setBm25(results.size());
        resp.getChannels().setVector(0);
        resp.getChannels().setFused(0);
        attachStages(resp, System.currentTimeMillis() - start, bm25Ms, true);
        log.info("[search][EXACT_TEXT_SEARCH phrase={}, exactReturned={}, exactTotal={}, totalExact={}, candidateTotal={}, elapsedMs={}]",
                StrUtil.maxLength(req.getExactText(), 80), results.size(), resp.getTotalHits(),
                resp.getTotalHitsExact(), searchHits.totalHits(), System.currentTimeMillis() - start);
        return resp;
    }

    private RetrievalSearchRespDTO base(RetrievalSearchReqDTO req) {
        RetrievalSearchRespDTO resp = new RetrievalSearchRespDTO();
        resp.setQuery(req != null ? req.getQuery() : null);
        resp.setIntent("EXACT_TEXT_SEARCH");
        resp.setQuestionProducts(List.of());
        RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
        analysis.setIntent("EXACT_TEXT_SEARCH");
        analysis.setEntities(List.of());
        analysis.setRewrites(List.of());
        analysis.setSubQuestions(List.of());
        analysis.setSuccess(true);
        analysis.setRoute("HYBRID_RAG");
        resp.setAnalysis(analysis);
        RetrievalSearchRespDTO.RetrievalChannelStatDTO channels = new RetrievalSearchRespDTO.RetrievalChannelStatDTO();
        channels.setBm25(0);
        channels.setVector(0);
        channels.setFused(0);
        resp.setChannels(channels);
        return resp;
    }

    private void attachStages(RetrievalSearchRespDTO resp, long elapsedMs, long bm25Ms, boolean executed) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        stages.add(stage("ANALYZE", ++seq, 0, "SKIPPED", true));
        stages.add(stage("ROUTE", ++seq, 0, "SUCCEEDED", false));
        stages.add(stage("SCOPE_FILTER", ++seq, Math.max(0, elapsedMs - bm25Ms), "SUCCEEDED", false));
        stages.add(stage("BM25", ++seq, bm25Ms, executed ? "SUCCEEDED" : "SKIPPED", !executed));
        stages.add(stage("EXACT_TEXT_VERIFY", ++seq, Math.max(0, elapsedMs - bm25Ms), executed ? "SUCCEEDED" : "SKIPPED", !executed));
        stages.add(stage("VECTOR", ++seq, 0, "SKIPPED", true));
        stages.add(stage("FUSION", ++seq, 0, "SKIPPED", true));
        stages.add(stage("RERANK", ++seq, 0, "SKIPPED", true));
        if (resp.getAnalysis() != null) resp.getAnalysis().setStages(stages);
    }

    private QueryStageTimingDTO stage(String name, int seq, long elapsedMs, String status, boolean skipped) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage(name);
        dto.setSeq(seq);
        dto.setElapsedMs(elapsedMs);
        dto.setStatus(status);
        dto.setSkipped(skipped);
        return dto;
    }
}
