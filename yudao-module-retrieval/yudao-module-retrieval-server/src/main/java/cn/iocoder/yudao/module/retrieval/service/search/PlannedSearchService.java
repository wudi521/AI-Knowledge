package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallContext;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query Engine V3 的纯执行检索器。
 *
 * <p>调用方已经完成自然语言规划，本服务禁止再次调用 QueryAnalysis/意图路由。
 * 核心流程固定为：领域 Recall 插件 -> RRF Fusion -> Rerank -> 权限/发布过滤。
 * BM25、Vector 只是当前两个通用 Recall 插件；新增领域召回能力不再修改本类。</p>
 */
@Service
public class PlannedSearchService {

    private static final int RECALL_TOP_K = 20;
    private static final int VARIANT_LIMIT = 6;

    private final RetrievalRecallPipeline recallPipeline;
    private final RetrievalDomainResolver domainResolver;
    private final RrfMerger rrfMerger;
    private final Reranker reranker;
    private final ResultFilter resultFilter;

    public PlannedSearchService(RetrievalRecallPipeline recallPipeline,
                                RetrievalDomainResolver domainResolver,
                                RrfMerger rrfMerger,
                                Reranker reranker,
                                ResultFilter resultFilter) {
        this.recallPipeline = recallPipeline;
        this.domainResolver = domainResolver;
        this.rrfMerger = rrfMerger;
        this.reranker = reranker;
        this.resultFilter = resultFilter;
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
        String domainCode = domainResolver.resolve(req.getDomainCode(), kbIds);

        RetrievalRecallContext recallContext = new RetrievalRecallContext(
                req.getQuery(), variants, req.getTenantId(), kbIds,
                req.getDocumentIds(), RECALL_TOP_K, domainCode);
        List<RetrievalRecallResult> recallResults = recallPipeline.recall(recallContext);
        List<List<Map.Entry<Long, Double>>> rankedLists = new ArrayList<>();
        Map<String, Set<Long>> channelIds = new LinkedHashMap<>();
        Map<String, Integer> channelCounts = new LinkedHashMap<>();
        for (RetrievalRecallResult recall : recallResults) {
            rankedLists.add(recall.hits());
            Set<Long> ids = channelIds.computeIfAbsent(recall.channel(), ignored -> new LinkedHashSet<>());
            for (Map.Entry<Long, Double> hit : recall.hits()) ids.add(hit.getKey());
            channelCounts.put(recall.channel(), ids.size());
            stages.add(stage(recall.channel().toUpperCase(Locale.ROOT), ++seq, recall.elapsedMs(),
                    "plugin=" + recall.pluginId() + "; domain=" + domainCode
                            + "; variants=" + variants + "; kbIds=" + kbIds
                            + "; documentIds=" + safeList(req.getDocumentIds()),
                    "hits=" + recall.hits().size() + "; degraded=" + recall.degraded()
                            + (StrUtil.isBlank(recall.message()) ? "" : "; message=" + recall.message())));
        }
        channels.setBm25(channelIds.getOrDefault("bm25", Set.of()).size());
        channels.setVector(channelIds.getOrDefault("vector", Set.of()).size());

        long start = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> fused = rrfMerger.merge(rankedLists, RECALL_TOP_K);
        Set<Long> published = resultFilter.filterPublished(fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        fused = fused.stream().filter(e -> published.contains(e.getKey())).toList();
        long fusionMs = System.currentTimeMillis() - start;
        channels.setFused(fused.size());
        stages.add(stage("FUSION", ++seq, fusionMs,
                "domain=" + domainCode + "; channels=" + channelCounts,
                "publishedFused=" + fused.size()));

        List<Long> candidateIds = fused.stream().map(Map.Entry::getKey).toList();
        Map<Long, String> contents = resultFilter.getChunkContents(candidateIds);
        Map<Long, ChunkDocInfoDTO> docInfo = resultFilter.getChunkDocInfo(candidateIds);
        Map<Long, String> metadata = resultFilter.getChunkMetadatas(candidateIds);
        List<String> candidateContents = candidateIds.stream().map(id -> contents.getOrDefault(id, "")).toList();
        Map<Long, Double> rrfScores = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

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
            for (Map.Entry<String, Set<Long>> channel : channelIds.entrySet()) {
                if (channel.getValue().contains(chunkId)) hitChannels.add(channel.getKey());
            }
            item.setChannels(hitChannels);
            item.setChunkMetadata(metadata.get(chunkId));
            results.add(item);
        }
        resp.setResults(results);
        stages.add(stage("RERANK", ++seq, rerankMs,
                "domain=" + domainCode + "; query=" + req.getQuery() + "; candidates=" + candidateIds.size(),
                "topResults=" + summarize(results)));
        analysis.setStages(stages);
        return resp;
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
