package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalDomainResolver;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeContext;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopeDecision;
import cn.iocoder.yudao.module.retrieval.service.search.scope.RetrievalScopePipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EXACT_TEXT_SEARCH 快路径。
 *
 * <p>与 Planned Hybrid 共用 Visibility + Domain Resolution + Scope 合同；差异仅在 Recall/Verify：
 * ES match_phrase 只做候选召回，随后必须对原文 content.contains(exactText) 做逐字二次校验。</p>
 */
@Slf4j
@Service
public class ExactTextRetrievalService {

    private static final int MAX_RETURN = 20;
    private static final int MAX_EXACT_CANDIDATES = 200;

    private final Bm25Searcher bm25Searcher;
    private final ResultFilter resultFilter;
    private final RetrievalDomainResolver domainResolver;
    private final RetrievalScopePipeline scopePipeline;

    public ExactTextRetrievalService(Bm25Searcher bm25Searcher,
                                     ResultFilter resultFilter,
                                     RetrievalDomainResolver domainResolver,
                                     RetrievalScopePipeline scopePipeline) {
        this.bm25Searcher = bm25Searcher;
        this.resultFilter = resultFilter;
        this.domainResolver = domainResolver;
        this.scopePipeline = scopePipeline;
    }

    public RetrievalSearchRespDTO search(RetrievalSearchReqDTO req) {
        long start = System.currentTimeMillis();
        RetrievalSearchRespDTO resp = base(req);
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        addStage(stages, "ANALYZE", 0, "SKIPPED", true, null, null);
        addStage(stages, "ROUTE", 0, "SUCCEEDED", false, "mode=EXACT_TEXT_SEARCH", "route=EXACT_TEXT_SEARCH");

        if (req == null || StrUtil.isBlank(req.getExactText())) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("精确原文检索缺少目标短语");
            resp.getAnalysis().setSuccess(false);
            resp.getAnalysis().setBlocked(true);
            resp.getAnalysis().setBlockReason("exact text is blank");
            clearExactTotals(resp);
            resp.setResults(List.of());
            finish(resp, stages);
            return resp;
        }

        long visibilityStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Set<Long>> visibility = resultFilter.getVisibleKbIdsResult(req.getUserId());
        addStage(stages, "VISIBILITY", System.currentTimeMillis() - visibilityStart,
                visibility.failed() ? "FAILED" : "SUCCEEDED", false,
                "userId=" + req.getUserId(),
                visibility.failed() ? "failed=" + visibility.errorMessage() : "visibleKbIds=" + visibility.data());
        if (visibility.failed()) {
            return failSource(resp, stages);
        }

        Set<Long> visible = visibility.data();
        List<Long> kbIds = req.getKbIds() != null && !req.getKbIds().isEmpty()
                ? req.getKbIds().stream().filter(visible::contains).distinct().toList()
                : new ArrayList<>(visible);
        if (kbIds.isEmpty()) {
            resp.getAnalysis().setBlocked(true);
            resp.getAnalysis().setBlockReason("no visible knowledge base in requested scope");
            resp.setResults(List.of());
            clearExactTotals(resp);
            finish(resp, stages);
            return resp;
        }

        long domainStart = System.currentTimeMillis();
        RetrievalDomainResolver.Resolution domain = domainResolver.resolveWithStatus(req.getDomainCode(), kbIds);
        addStage(stages, "DOMAIN_RESOLUTION", System.currentTimeMillis() - domainStart,
                domain.failed() ? "FAILED" : "SUCCEEDED", false,
                "requestedDomain=" + StrUtil.blankToDefault(req.getDomainCode(), "<registry>") + "; kbIds=" + kbIds,
                "domain=" + domain.domainCode() + "; mixed=" + domain.mixedDomainScope()
                        + suffix(domain.message()));
        if (domain.failed()) {
            return failSource(resp, stages);
        }
        if (domain.mixedDomainScope()) {
            resp.getAnalysis().setBlocked(true);
            resp.getAnalysis().setBlockReason("mixed-domain knowledge scope must be partitioned by domain before retrieval");
            resp.setResults(List.of());
            clearExactTotals(resp);
            finish(resp, stages);
            return resp;
        }

        long scopeStart = System.currentTimeMillis();
        RetrievalScopePipeline.Result scope = scopePipeline.refine(new RetrievalScopeContext(
                req.getQuery(), req.getTenantId(), kbIds, req.getDocumentIds(), domain.domainCode()));
        addStage(stages, "SCOPE", System.currentTimeMillis() - scopeStart,
                scope.degraded() ? "DEGRADED" : "SUCCEEDED", false,
                "domain=" + domain.domainCode() + "; initialDocumentIds=" + safeList(req.getDocumentIds()),
                "documentIds=" + scope.documentIds() + "; blocked=" + scope.blocked()
                        + "; decisions=" + scopeSummary(scope.decisions()));
        if (scope.blocked()) {
            resp.getAnalysis().setBlocked(true);
            resp.getAnalysis().setBlockReason(blockReason(scope.decisions()));
            resp.getAnalysis().setDegraded(scope.degraded());
            resp.getAnalysis().setSuccess(!scope.degraded());
            resp.setResults(List.of());
            clearExactTotals(resp);
            finish(resp, stages);
            return resp;
        }

        int returnLimit = req.getTopK() == null || req.getTopK() <= 0 ? 10 : Math.min(req.getTopK(), MAX_RETURN);
        long bm25Start = System.currentTimeMillis();
        Bm25Searcher.ExactSearchExecution execution = bm25Searcher.searchExactPhraseWithStatus(
                req.getExactText(), req.getTenantId(), kbIds, MAX_EXACT_CANDIDATES,
                scope.documentIds().isEmpty() ? null : scope.documentIds());
        long bm25Ms = System.currentTimeMillis() - bm25Start;
        addStage(stages, "BM25_EXACT_PHRASE", bm25Ms, execution.failed() ? "FAILED" : "SUCCEEDED", false,
                "exactText=" + StrUtil.maxLength(req.getExactText(), 120) + "; documentIds=" + scope.documentIds(),
                execution.failed() ? "failed=" + execution.errorMessage()
                        : "candidateTotal=" + execution.searchHits().totalHits());
        if (execution.failed()) {
            return failSource(resp, stages);
        }

        Bm25Searcher.SearchHits searchHits = execution.searchHits();
        resp.setCandidateTotalHits(searchHits.totalHits());
        List<Map.Entry<Long, Double>> hits = searchHits.hits();
        List<Long> orderedIds = hits.stream().map(Map.Entry::getKey).distinct().toList();
        boolean allCandidatesInspected = searchHits.totalHits() <= MAX_EXACT_CANDIDATES;
        if (orderedIds.isEmpty()) {
            resp.setResults(List.of());
            resp.setTotalHits(allCandidatesInspected ? 0L : null);
            resp.setTotalHitsExact(allCandidatesInspected);
            resp.getChannels().setBm25(0);
            resp.getChannels().setRecall(Map.of("exact_text", 0));
            addStage(stages, "EXACT_TEXT_VERIFY", 0, "SUCCEEDED", false,
                    "candidateCount=0", "exactCount=0; complete=" + allCandidatesInspected);
            finish(resp, stages);
            return resp;
        }

        long publishStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Set<Long>> publishedRead = resultFilter.filterPublishedResult(new HashSet<>(orderedIds));
        addStage(stages, "PUBLISHED_FILTER", System.currentTimeMillis() - publishStart,
                publishedRead.failed() ? "FAILED" : "SUCCEEDED", false,
                "candidateCount=" + orderedIds.size(),
                publishedRead.failed() ? "failed=" + publishedRead.errorMessage()
                        : "publishedCount=" + publishedRead.data().size());
        if (publishedRead.failed()) {
            return failSource(resp, stages);
        }
        Set<Long> published = publishedRead.data();
        List<Long> candidateIds = orderedIds.stream().filter(published::contains).toList();

        long contentStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Map<Long, String>> contentsRead = resultFilter.getChunkContentsResult(candidateIds);
        boolean missingContentKeys = !contentsRead.failed() && !contentsRead.data().keySet().containsAll(candidateIds);
        addStage(stages, "CONTENT_HYDRATE", System.currentTimeMillis() - contentStart,
                contentsRead.failed() || missingContentKeys ? "FAILED" : "SUCCEEDED", false,
                "candidateIds=" + candidateIds,
                contentsRead.failed() ? "failed=" + contentsRead.errorMessage()
                        : "contentCount=" + contentsRead.data().size()
                        + (missingContentKeys ? "; missingContentKeys=true" : ""));
        if (contentsRead.failed() || missingContentKeys) {
            return failSource(resp, stages);
        }
        Map<Long, String> contents = contentsRead.data();

        long verifyStart = System.currentTimeMillis();
        List<Long> exactIds = candidateIds.stream()
                .filter(id -> contents.get(id) != null && contents.get(id).contains(req.getExactText()))
                .toList();
        long verifyMs = System.currentTimeMillis() - verifyStart;

        if (allCandidatesInspected) {
            resp.setTotalHits((long) exactIds.size());
            resp.setTotalHitsExact(true);
        } else {
            resp.setTotalHits(null);
            resp.setTotalHitsExact(false);
        }

        List<Long> returnIds = exactIds.stream().limit(returnLimit).toList();
        long provenanceStart = System.currentTimeMillis();
        ResultFilter.ReadResult<Map<Long, ChunkDocInfoDTO>> docInfoRead = resultFilter.getChunkDocInfoResult(returnIds);
        ResultFilter.ReadResult<Map<Long, String>> metadataRead = resultFilter.getChunkMetadatasResult(returnIds);
        boolean missingDocInfo = !docInfoRead.failed() && !docInfoRead.data().keySet().containsAll(returnIds);
        boolean provenanceFailed = docInfoRead.failed() || missingDocInfo;
        boolean metadataDegraded = metadataRead.failed();
        addStage(stages, "PROVENANCE_HYDRATE", System.currentTimeMillis() - provenanceStart,
                provenanceFailed ? "FAILED" : metadataDegraded ? "DEGRADED" : "SUCCEEDED", false,
                "returnIds=" + returnIds,
                "docInfo=" + docInfoRead.data().size() + "; metadata=" + metadataRead.data().size()
                        + (docInfoRead.failed() ? "; docInfoError=" + docInfoRead.errorMessage() : "")
                        + (missingDocInfo ? "; missingDocInfoKeys=true" : "")
                        + (metadataRead.failed() ? "; metadataError=" + metadataRead.errorMessage() : ""));
        if (provenanceFailed) {
            return failSource(resp, stages);
        }
        if (metadataDegraded) {
            resp.getAnalysis().setDegraded(true);
        }

        Map<Long, String> metadata = metadataRead.data();
        Map<Long, ChunkDocInfoDTO> docInfo = docInfoRead.data();
        Map<Long, Double> scores = hits.stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, Math::max));

        List<RetrievalResultDTO> results = new ArrayList<>();
        for (Long id : returnIds) {
            RetrievalResultDTO item = new RetrievalResultDTO();
            item.setChunkId(id);
            item.setContent(contents.get(id));
            ChunkDocInfoDTO info = docInfo.get(id);
            item.setDocumentId(info.getDocumentId());
            item.setDocumentName(info.getDocumentName());
            item.setVersionNo(info.getVersionNo());
            item.setVersionId(info.getVersionId());
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
        resp.getChannels().setRecall(Map.of("exact_text", results.size()));
        addStage(stages, "EXACT_TEXT_VERIFY", verifyMs, "SUCCEEDED", false,
                "candidateCount=" + candidateIds.size(),
                "exactCount=" + exactIds.size() + "; returned=" + results.size()
                        + "; complete=" + allCandidatesInspected);
        finish(resp, stages);
        log.info("[search][EXACT_TEXT_SEARCH domain={}, phrase={}, exactReturned={}, exactTotal={}, totalExact={}, candidateTotal={}, elapsedMs={}]",
                domain.domainCode(), StrUtil.maxLength(req.getExactText(), 80), results.size(), resp.getTotalHits(),
                resp.getTotalHitsExact(), searchHits.totalHits(), System.currentTimeMillis() - start);
        return resp;
    }

    private RetrievalSearchRespDTO failSource(RetrievalSearchRespDTO resp, List<QueryStageTimingDTO> stages) {
        resp.getAnalysis().setSuccess(false);
        resp.getAnalysis().setDegraded(true);
        resp.setResults(List.of());
        clearExactTotals(resp);
        finish(resp, stages);
        return resp;
    }

    private void clearExactTotals(RetrievalSearchRespDTO resp) {
        resp.setTotalHits(null);
        resp.setTotalHitsExact(false);
        resp.setCandidateTotalHits(null);
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
        analysis.setDegraded(false);
        analysis.setBlocked(false);
        analysis.setRoute("EXACT_TEXT_PLUGIN_SCOPE");
        resp.setAnalysis(analysis);
        RetrievalSearchRespDTO.RetrievalChannelStatDTO channels = new RetrievalSearchRespDTO.RetrievalChannelStatDTO();
        channels.setBm25(0);
        channels.setVector(0);
        channels.setFused(0);
        channels.setRecall(Map.of());
        resp.setChannels(channels);
        return resp;
    }

    private void finish(RetrievalSearchRespDTO resp, List<QueryStageTimingDTO> stages) {
        addStage(stages, "VECTOR", 0, "SKIPPED", true, null, null);
        addStage(stages, "FUSION", 0, "SKIPPED", true, null, null);
        addStage(stages, "RERANK", 0, "SKIPPED", true, null, null);
        if (resp.getAnalysis() != null) resp.getAnalysis().setStages(List.copyOf(stages));
    }

    private void addStage(List<QueryStageTimingDTO> stages, String name, long elapsedMs,
                          String status, boolean skipped, String input, String output) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage(name);
        dto.setSeq(stages.size() + 1);
        dto.setElapsedMs(elapsedMs);
        dto.setStatus(status);
        dto.setSkipped(skipped);
        dto.setInputSummary(input);
        dto.setOutputSummary(output);
        stages.add(dto);
    }

    private String scopeSummary(List<RetrievalScopeDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) return "[]";
        return decisions.stream().map(d -> "{plugin=" + d.pluginId() + ",applied=" + d.applied()
                        + ",blocked=" + d.blocked() + ",degraded=" + d.degraded() + suffix(d.message()) + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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

    private String safeList(List<Long> ids) {
        return ids == null ? "[]" : ids.toString();
    }
}
