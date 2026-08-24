package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query Engine V3 的纯执行检索器。
 *
 * <p>调用方已经完成自然语言规划，本服务禁止再次调用 QueryAnalysis/意图路由；
 * 只执行有限 query variants 的 BM25 + Vector + RRF + Rerank，并返回真实阶段数据。</p>
 */
@Slf4j
@Service
public class PlannedSearchService {

    private static final int RECALL_TOP_K = 20;
    private static final int VARIANT_LIMIT = 6;

    private final Bm25Searcher bm25Searcher;
    private final VectorSearcher vectorSearcher;
    private final RrfMerger rrfMerger;
    private final Reranker reranker;
    private final ResultFilter resultFilter;
    private final ModelApi modelApi;

    public PlannedSearchService(Bm25Searcher bm25Searcher,
                                VectorSearcher vectorSearcher,
                                RrfMerger rrfMerger,
                                Reranker reranker,
                                ResultFilter resultFilter,
                                ModelApi modelApi) {
        this.bm25Searcher = bm25Searcher;
        this.vectorSearcher = vectorSearcher;
        this.rrfMerger = rrfMerger;
        this.reranker = reranker;
        this.resultFilter = resultFilter;
        this.modelApi = modelApi;
    }

    public RetrievalSearchRespDTO search(RetrievalSearchReqDTO req) {
        RetrievalSearchRespDTO resp = new RetrievalSearchRespDTO();
        if (req == null || StrUtil.isBlank(req.getQuery()) || req.getUserId() == null) {
            resp.setResults(List.of());
            return resp;
        }
        resp.setQuery(req.getQuery());
        RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
        analysis.setIntent("PLANNED_SEARCH");
        analysis.setRoute("PLANNED_HYBRID");
        analysis.setSuccess(true);
        resp.setAnalysis(analysis);
        RetrievalSearchRespDTO.RetrievalChannelStatDTO channels = new RetrievalSearchRespDTO.RetrievalChannelStatDTO();
        resp.setChannels(channels);

        Set<Long> visible = resultFilter.getVisibleKbIds(req.getUserId());
        List<Long> kbIds = req.getKbIds() != null && !req.getKbIds().isEmpty()
                ? req.getKbIds().stream().filter(visible::contains).distinct().toList()
                : new ArrayList<>(visible);
        if (kbIds.isEmpty()) {
            resp.setResults(List.of());
            return resp;
        }

        List<String> variants = new ArrayList<>();
        variants.add(req.getQuery().trim());
        if (req.getQueryVariants() != null) variants.addAll(req.getQueryVariants());
        variants = variants.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().limit(VARIANT_LIMIT).toList();
        analysis.setRewrites(variants.size() <= 1 ? List.of() : variants.subList(1, variants.size()));
        analysis.setSubQuestions(List.of());
        analysis.setEntities(List.of());

        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        long start;

        start = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> bm25 = new ArrayList<>();
        for (String variant : variants) {
            bm25.addAll(bm25Searcher.search(variant, req.getTenantId(), kbIds, RECALL_TOP_K, req.getDocumentIds()));
        }
        bm25 = dedupeMax(bm25);
        long bm25Ms = System.currentTimeMillis() - start;
        channels.setBm25(bm25.size());
        stages.add(stage("BM25", ++seq, bm25Ms,
                "variants=" + variants + "; kbIds=" + kbIds + "; documentIds=" + safeList(req.getDocumentIds()),
                "hits=" + bm25.size()));

        start = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> vector = vectorSearch(variants, req.getTenantId(), kbIds, req.getDocumentIds());
        vector = dedupeMin(vector);
        long vectorMs = System.currentTimeMillis() - start;
        channels.setVector(vector.size());
        stages.add(stage("VECTOR", ++seq, vectorMs,
                "variants=" + variants + "; hardScopeDocuments=" + safeList(req.getDocumentIds()),
                "hits=" + vector.size()));

        start = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> fused = rrfMerger.merge(List.of(bm25, vector), RECALL_TOP_K);
        Set<Long> published = resultFilter.filterPublished(fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        fused = fused.stream().filter(e -> published.contains(e.getKey())).toList();
        long fusionMs = System.currentTimeMillis() - start;
        channels.setFused(fused.size());
        stages.add(stage("FUSION", ++seq, fusionMs,
                "bm25Hits=" + bm25.size() + "; vectorHits=" + vector.size(),
                "publishedFused=" + fused.size()));

        List<Long> candidateIds = fused.stream().map(Map.Entry::getKey).toList();
        Map<Long, String> contents = resultFilter.getChunkContents(candidateIds);
        Map<Long, ChunkDocInfoDTO> docInfo = resultFilter.getChunkDocInfo(candidateIds);
        Map<Long, String> metadata = resultFilter.getChunkMetadatas(candidateIds);
        List<String> candidateContents = candidateIds.stream().map(id -> contents.getOrDefault(id, "")).toList();
        Map<Long, Double> rrfScores = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<Long> bm25Ids = bm25.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<Long> vectorIds = vector.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        start = System.currentTimeMillis();
        List<Map.Entry<Integer, Float>> reranked;
        if (candidateContents.isEmpty() || candidateContents.stream().allMatch(StrUtil::isBlank)) {
            reranked = new ArrayList<>();
            for (int i = 0; i < candidateContents.size(); i++) reranked.add(Map.entry(i, 0F));
        } else {
            reranked = reranker.rerank(req.getQuery(), candidateContents);
        }
        long rerankMs = System.currentTimeMillis() - start;

        int topK = req.getTopK() == null || req.getTopK() <= 0 ? 8 : Math.min(req.getTopK(), RECALL_TOP_K);
        List<RetrievalResultDTO> results = new ArrayList<>();
        for (Map.Entry<Integer, Float> ranked : reranked) {
            if (results.size() >= topK) break;
            int idx = ranked.getKey();
            if (idx < 0 || idx >= candidateIds.size()) continue;
            Long chunkId = candidateIds.get(idx);
            ChunkDocInfoDTO info = docInfo.get(chunkId);
            if (req.getDocumentIds() != null && !req.getDocumentIds().isEmpty()
                    && (info == null || info.getDocumentId() == null || !req.getDocumentIds().contains(info.getDocumentId()))) {
                continue;
            }
            RetrievalResultDTO item = new RetrievalResultDTO();
            item.setChunkId(chunkId);
            item.setContent(contents.getOrDefault(chunkId, ""));
            if (info != null) {
                item.setDocumentId(info.getDocumentId());
                item.setDocumentName(info.getDocumentName());
                item.setVersionNo(info.getVersionNo());
                item.setVersionId(info.getVersionId());
            }
            item.setRrfScore(rrfScores.get(chunkId));
            item.setRerankScore(ranked.getValue());
            List<String> hitChannels = new ArrayList<>();
            if (bm25Ids.contains(chunkId)) hitChannels.add("bm25");
            if (vectorIds.contains(chunkId)) hitChannels.add("vector");
            item.setChannels(hitChannels);
            item.setChunkMetadata(metadata.get(chunkId));
            results.add(item);
        }
        resp.setResults(results);
        stages.add(stage("RERANK", ++seq, rerankMs,
                "query=" + req.getQuery() + "; candidates=" + candidateIds.size(),
                "topResults=" + summarize(results)));
        analysis.setStages(stages);
        return resp;
    }

    private List<Map.Entry<Long, Double>> vectorSearch(List<String> variants, Long tenantId,
                                                        List<Long> kbIds, List<Long> documentIds) {
        try {
            List<List<Float>> vectors = modelApi.embedding(variants).getCheckedData();
            if (vectors == null || vectors.isEmpty()) return List.of();
            return vectorSearcher.search(vectors, tenantId, kbIds, RECALL_TOP_K, documentIds);
        } catch (Exception e) {
            log.warn("[plannedSearch][vector skipped: {}]", e.getMessage());
            return List.of();
        }
    }

    private List<Map.Entry<Long, Double>> dedupeMax(List<Map.Entry<Long, Double>> list) {
        Map<Long, Double> map = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : list) map.merge(e.getKey(), e.getValue(), Math::max);
        return new ArrayList<>(map.entrySet()).stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()).toList();
    }

    /** VectorSearcher 的值为 1-cosine，越小越相关。 */
    private List<Map.Entry<Long, Double>> dedupeMin(List<Map.Entry<Long, Double>> list) {
        Map<Long, Double> map = new HashMap<>();
        for (Map.Entry<Long, Double> e : list) map.merge(e.getKey(), e.getValue(), Math::min);
        return map.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList();
    }

    private QueryStageTimingDTO stage(String name, int seq, long ms, String input, String output) {
        QueryStageTimingDTO stage = new QueryStageTimingDTO();
        stage.setStage(name);
        stage.setSeq(seq);
        stage.setStatus("SUCCEEDED");
        stage.setElapsedMs(ms);
        stage.setSkipped(false);
        stage.setInputSummary(limit(input));
        stage.setOutputSummary(limit(output));
        return stage;
    }

    private String summarize(List<RetrievalResultDTO> results) {
        if (results == null || results.isEmpty()) return "[]";
        return results.stream().limit(5).map(r -> "{chunk=" + r.getChunkId()
                + ",doc=" + r.getDocumentId() + ",score=" + r.getRerankScore()
                + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(r.getDocumentName()), 60) + "}")
                .collect(Collectors.joining(",", "[", results.size() > 5 ? ",...]" : "]"));
    }

    private String safeList(List<Long> ids) {
        return ids == null ? "[]" : ids.toString();
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 950 ? value : value.substring(0, 950) + "...";
    }
}
