package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionContext;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.fusion.RetrievalFusionResult;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallContext;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallResult;
import cn.iocoder.yudao.module.retrieval.service.search.rerank.RetrievalRerankContext;
import cn.iocoder.yudao.module.retrieval.service.search.rerank.RetrievalRerankPipeline;
import cn.iocoder.yudao.module.retrieval.service.search.rerank.RetrievalRerankResult;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeContext;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeDecision;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopePipeline;
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
 * 核心只编排强类型扩展阶段：Visibility -> Domain Resolution -> Scope[] -> Recall[] ->
 * Fusion -> Published Filter -> Hydration -> Rerank。</p>
 *
 * <p>一次执行只绑定一个 domainCode。多领域 KB scope 必须由上游先按领域分区，
 * 禁止把专业领域规则降成 GENERAL 后继续检索。</p>
 */
@Service
public class PlannedSearchService {

    private static final int RECALL_TOP_K = 20;
    private static final int VARIANT_LIMIT = 6;

    private final RetrievalScopePipeline scopePipeline;
    private final RetrievalRecallPipeline recallPipeline;
    private final RetrievalFusionPipeline fusionPipeline;
    private final RetrievalRerankPipeline rerankPipeline;
    private final RetrievalDomainResolver domainResolver;
    private final ResultFilter resultFilter;

    public PlannedSearchService(RetrievalScopePipeline scopePipeline,
                                RetrievalRecallPipeline recallPipeline,
                                RetrievalFusionPipeline fusionPipeline,
                                RetrievalRerankPipeline rerankPipeline,
                                RetrievalDomainResolver domainResolver,
                                ResultFilter resultFilter) {
        this.scopePipeline = scopePipeline;
        this.recallPipeline = recallPipeline;
        this.fusionPipeline = fusionPipeline;
        this.rerankPipeline = rerankPipeline;
        this.domainResolver = domainResolver;
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
        analysis.setRoute("PLANNED_PLUGIN_PIPELINE");
        analysis.setSuccess(true);
        analysis.setDegraded(false);
        analysis.setBlocked(false);
        resp.setAnalysis(analysis);
        RetrievalSearchRespDTO.RetrievalChannelStatDTO channels = new RetrievalSearchRespDTO.RetrievalChannelStatDTO();
        channels.setRecall(Map.of());
        resp.setChannels(channels);

        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;

        long visibilityStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Set<Long>> visibility = resultFilter.getVisibleKbIdsResult(req.getUserId());
        stages.add(stage("VISIBILITY", ++seq, System.currentTimeMillis() - visibilityStart,
                visibility.failed() ? "FAILED" : "SUCCEEDED",
                "userId=" + req.getUserId(),
                visibility.failed() ? "failed=" + visibility.errorMessage()
                        : "visibleKbIds=" + visibility.data()));
        if (visibility.failed()) {
            return failSource(resp, analysis, channels, stages);
        }

        Set<Long> visible = visibility.data();
        List<Long> kbIds = req.getKbIds() != null && !req.getKbIds().isEmpty()
                ? req.getKbIds().stream().filter(visible::contains).distinct().toList()
                : new ArrayList<>(visible);
        if (kbIds.isEmpty()) {
            analysis.setBlocked(true);
            analysis.setBlockReason("no visible knowledge base in requested scope");
            zeroChannels(channels);
            resp.setResults(List.of());
            analysis.setStages(stages);
            return resp;
        }

        List<String> variants = new ArrayList<>();
        variants.add(req.getQuery().trim());
        if (req.getQueryVariants() != null) variants.addAll(req.getQueryVariants());
        variants = variants.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().limit(VARIANT_LIMIT).toList();
        analysis.setRewrites(variants.size() <= 1 ? List.of() : variants.subList(1, variants.size()));
        analysis.setSubQuestions(List.of());
        analysis.setEntities(List.of());

        long domainStart = System.currentTimeMillis();
        RetrievalDomainResolver.Resolution domainResolution = domainResolver.resolveWithStatus(req.getDomainCode(), kbIds);
        long domainMs = System.currentTimeMillis() - domainStart;
        stages.add(stage("DOMAIN_RESOLUTION", ++seq, domainMs,
                domainResolution.failed() ? "FAILED" : "SUCCEEDED",
                "requestedDomain=" + StrUtil.blankToDefault(req.getDomainCode(), "<registry>") + "; kbIds=" + kbIds,
                "domain=" + domainResolution.domainCode() + "; failed=" + domainResolution.failed()
                        + "; mixed=" + domainResolution.mixedDomainScope() + suffix(domainResolution.message())));
        if (domainResolution.failed()) {
            return failSource(resp, analysis, channels, stages);
        }
        if (domainResolution.mixedDomainScope()) {
            analysis.setBlocked(true);
            analysis.setBlockReason("mixed-domain knowledge scope must be partitioned by domain before retrieval");
            analysis.setSuccess(true);
            analysis.setDegraded(false);
            analysis.setStages(stages);
            zeroChannels(channels);
            resp.setResults(List.of());
            return resp;
        }
        String domainCode = domainResolution.domainCode();

        long scopeStart = System.currentTimeMillis();
        RetrievalScopePipeline.Result scope = scopePipeline.refine(new RetrievalScopeContext(
                req.getQuery(), req.getTenantId(), kbIds, req.getDocumentIds(), domainCode));
        long scopeMs = System.currentTimeMillis() - scopeStart;
        stages.add(stage("SCOPE", ++seq, scopeMs,
                scope.degraded() ? "DEGRADED" : "SUCCEEDED",
                "domain=" + domainCode + "; initialDocumentIds=" + safeList(req.getDocumentIds()),
                "documentIds=" + scope.documentIds() + "; blocked=" + scope.blocked()
                        + "; degraded=" + scope.degraded() + "; decisions=" + scopeSummary(scope.decisions())));
        if (scope.blocked()) {
            boolean degraded = scope.degraded();
            analysis.setBlocked(true);
            analysis.setBlockReason(blockReason(scope.decisions()));
            analysis.setDegraded(degraded);
            analysis.setSuccess(!degraded);
            analysis.setStages(stages);
            zeroChannels(channels);
            resp.setResults(List.of());
            return resp;
        }

        RetrievalRecallContext recallContext = new RetrievalRecallContext(
                req.getQuery(), variants, req.getTenantId(), kbIds,
                scope.documentIds(), RECALL_TOP_K, domainCode);
        List<RetrievalRecallResult> recallResults = recallPipeline.recall(recallContext);
        boolean degraded = scope.degraded() || recallResults.stream().anyMatch(RetrievalRecallResult::degraded);
        Map<String, Set<Long>> channelIds = new LinkedHashMap<>();
        Map<String, Integer> channelCounts = new LinkedHashMap<>();
        for (RetrievalRecallResult recall : recallResults) {
            Set<Long> ids = channelIds.computeIfAbsent(recall.channel(), ignored -> new LinkedHashSet<>());
            for (Map.Entry<Long, Double> hit : recall.hits()) ids.add(hit.getKey());
            channelCounts.put(recall.channel(), ids.size());
            stages.add(stage(recall.channel().toUpperCase(Locale.ROOT), ++seq, recall.elapsedMs(),
                    recall.degraded() ? "DEGRADED" : "SUCCEEDED",
                    "plugin=" + recall.pluginId() + "; domain=" + domainCode
                            + "; variants=" + variants + "; kbIds=" + kbIds
                            + "; documentIds=" + scope.documentIds(),
                    "hits=" + recall.hits().size() + "; degraded=" + recall.degraded()
                            + suffix(recall.message())));
        }
        channels.setRecall(Map.copyOf(channelCounts));
        channels.setBm25(channelIds.getOrDefault("bm25", Set.of()).size());
        channels.setVector(channelIds.getOrDefault("vector", Set.of()).size());

        long fusionStart = System.currentTimeMillis();
        RetrievalFusionResult fusion = fusionPipeline.fuse(
                new RetrievalFusionContext(domainCode, recallResults, RECALL_TOP_K));
        degraded |= fusion.degraded();
        List<Map.Entry<Long, Double>> fused = fusion.hits();
        long fusionMs = System.currentTimeMillis() - fusionStart;
        stages.add(stage("FUSION", ++seq, fusionMs,
                fusion.degraded() ? "DEGRADED" : "SUCCEEDED",
                "plugin=" + fusion.pluginId() + "; domain=" + domainCode + "; channels=" + channelCounts,
                "fused=" + fused.size() + "; degraded=" + fusion.degraded() + suffix(fusion.message())));

        long publishStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Set<Long>> publishedRead = resultFilter.filterPublishedResult(
                fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        stages.add(stage("PUBLISHED_FILTER", ++seq, System.currentTimeMillis() - publishStart,
                publishedRead.failed() ? "FAILED" : "SUCCEEDED",
                "candidateCount=" + fused.size(),
                publishedRead.failed() ? "failed=" + publishedRead.errorMessage()
                        : "publishedCount=" + publishedRead.data().size()));
        if (publishedRead.failed()) {
            return failSource(resp, analysis, channels, stages);
        }
        Set<Long> published = publishedRead.data();
        fused = fused.stream().filter(e -> published.contains(e.getKey())).toList();
        channels.setFused(fused.size());

        List<Long> candidateIds = fused.stream().map(Map.Entry::getKey).toList();
        long hydrateStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Map<Long, String>> contentsRead = resultFilter.getChunkContentsResult(candidateIds);
        ResultFilter.ReadResult<Map<Long, ChunkDocInfoDTO>> docInfoRead = resultFilter.getChunkDocInfoResult(candidateIds);
        ResultFilter.ReadResult<Map<Long, String>> metadataRead = resultFilter.getChunkMetadatasResult(candidateIds);
        boolean missingContents = !contentsRead.failed() && !contentsRead.data().keySet().containsAll(candidateIds);
        boolean missingDocInfo = !docInfoRead.failed() && !docInfoRead.data().keySet().containsAll(candidateIds);
        boolean hydrateFailed = contentsRead.failed() || docInfoRead.failed() || missingContents || missingDocInfo;
        boolean metadataDegraded = metadataRead.failed();
        String hydrateOutput = "contents=" + contentsRead.data().size()
                + "; docInfo=" + docInfoRead.data().size()
                + "; metadata=" + metadataRead.data().size()
                + (contentsRead.failed() ? "; contentError=" + contentsRead.errorMessage() : "")
                + (docInfoRead.failed() ? "; docInfoError=" + docInfoRead.errorMessage() : "")
                + (missingContents ? "; missingContentKeys=true" : "")
                + (missingDocInfo ? "; missingDocInfoKeys=true" : "")
                + (metadataRead.failed() ? "; metadataError=" + metadataRead.errorMessage() : "");
        stages.add(stage("HYDRATE", ++seq, System.currentTimeMillis() - hydrateStart,
                hydrateFailed ? "FAILED" : metadataDegraded ? "DEGRADED" : "SUCCEEDED",
                "candidateIds=" + candidateIds,
                hydrateOutput));
        if (hydrateFailed) {
            return failSource(resp, analysis, channels, stages);
        }
        degraded |= metadataDegraded;

        Map<Long, String> contents = contentsRead.data();
        Map<Long, ChunkDocInfoDTO> docInfo = docInfoRead.data();
        Map<Long, String> metadata = metadataRead.data();
        List<String> candidateContents = candidateIds.stream().map(id -> contents.getOrDefault(id, "")).toList();
        Map<Long, Double> fusionScores = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        RetrievalRerankResult rerank = rerankPipeline.rerank(
                new RetrievalRerankContext(req.getQuery(), candidateContents, domainCode, scope.documentIds()));
        degraded |= rerank.degraded();
        List<Map.Entry<Integer, Float>> reranked = rerank.rankings();

        int topK = req.getTopK() == null || req.getTopK() <= 0 ? 8 : Math.min(req.getTopK(), RECALL_TOP_K);
        List<RetrievalResultDTO> results = new ArrayList<>();
        for (Map.Entry<Integer, Float> ranked : reranked) {
            if (results.size() >= topK) break;
            int idx = ranked.getKey();
            if (idx < 0 || idx >= candidateIds.size()) continue;
            Long chunkId = candidateIds.get(idx);
            ChunkDocInfoDTO info = docInfo.get(chunkId);
            if (!scope.documentIds().isEmpty()
                    && (info.getDocumentId() == null || !scope.documentIds().contains(info.getDocumentId()))) {
                continue;
            }
            RetrievalResultDTO item = new RetrievalResultDTO();
            item.setChunkId(chunkId);
            item.setContent(contents.getOrDefault(chunkId, ""));
            item.setDocumentId(info.getDocumentId());
            item.setDocumentName(info.getDocumentName());
            item.setVersionNo(info.getVersionNo());
            item.setVersionId(info.getVersionId());
            item.setRrfScore(fusionScores.get(chunkId));
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
        stages.add(stage("RERANK", ++seq, rerank.elapsedMs(),
                rerank.degraded() ? "DEGRADED" : "SUCCEEDED",
                "plugin=" + rerank.pluginId() + "; domain=" + domainCode + "; query=" + req.getQuery()
                        + "; candidates=" + candidateIds.size() + "; scopedDocuments=" + scope.documentIds(),
                "topResults=" + summarize(results) + "; degraded=" + rerank.degraded() + suffix(rerank.message())));
        analysis.setDegraded(degraded);
        // 降级但仍拿到可靠候选可继续回答；降级且最终为空时，不能把它冒充正常零命中。
        analysis.setSuccess(!degraded || !results.isEmpty());
        analysis.setStages(stages);
        return resp;
    }

    private RetrievalSearchRespDTO failSource(RetrievalSearchRespDTO resp,
                                               RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                               RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                                               List<QueryStageTimingDTO> stages) {
        analysis.setSuccess(false);
        analysis.setDegraded(true);
        analysis.setStages(List.copyOf(stages));
        zeroChannelsIfNull(channels);
        resp.setResults(List.of());
        return resp;
    }

    private void zeroChannels(RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
        channels.setBm25(0);
        channels.setVector(0);
        channels.setFused(0);
    }

    private void zeroChannelsIfNull(RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
        if (channels.getBm25() == null) channels.setBm25(0);
        if (channels.getVector() == null) channels.setVector(0);
        if (channels.getFused() == null) channels.setFused(0);
    }

    private QueryStageTimingDTO stage(String name, int seq, long ms, String status, String input, String output) {
        QueryStageTimingDTO stage = new QueryStageTimingDTO();
        stage.setStage(name);
        stage.setSeq(seq);
        stage.setStatus(status);
        stage.setElapsedMs(ms);
        stage.setSkipped(false);
        stage.setInputSummary(limit(input));
        stage.setOutputSummary(limit(output));
        return stage;
    }

    private String scopeSummary(List<RetrievalScopeDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) return "[]";
        return decisions.stream().map(d -> "{plugin=" + d.pluginId() + ",applied=" + d.applied()
                        + ",blocked=" + d.blocked() + ",degraded=" + d.degraded() + suffix(d.message()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String blockReason(List<RetrievalScopeDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) return "hard scope resolved to empty set";
        String reason = null;
        for (RetrievalScopeDecision decision : decisions) {
            if (decision != null && decision.blocked()) reason = decision.message();
        }
        return StrUtil.blankToDefault(reason, "hard scope resolved to empty set");
    }

    private String suffix(String message) {
        return StrUtil.isBlank(message) ? "" : "; message=" + message;
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
