package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalReqVO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 检索编排: Query Planner → Exact/Scoped/Hybrid。 */
@Slf4j
@Service
public class SearchService {

    private static final int RECALL_TOP_K = 20;
    private static final int VARIANT_LIMIT = 6;

    @Resource private QueryAnalysisService queryAnalysisService;
    @Resource private KnowledgeApi knowledgeApi;
    @Resource private Bm25Searcher bm25Searcher;
    @Resource private VectorSearcher vectorSearcher;
    @Resource private RrfMerger rrfMerger;
    @Resource private Reranker reranker;
    @Resource private ResultFilter resultFilter;
    @Resource private ModelApi modelApi;
    @Resource private cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicyRegistry domainPolicyRegistry;
    @Resource private cn.iocoder.yudao.module.retrieval.dal.mysql.trace.RetrievalTraceMapper retrievalTraceMapper;

    public RetrievalRespVO search(RetrievalReqVO req) {
        return search(req.getQuery(), req.getKbIds(), req.getTopK(),
                SecurityFrameworkUtils.getLoginUser().getTenantId(), SecurityFrameworkUtils.getLoginUserId());
    }

    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId) {
        return search(query, reqKbIds, topK, tenantId, userId, null);
    }

    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId,
                                  List<ChatTurnDTO> history) {
        return search(query, reqKbIds, topK, tenantId, userId, history, null);
    }

    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId,
                                  List<ChatTurnDTO> history, String traceId) {
        long startMs = System.currentTimeMillis();
        int topKFinal = topK == null || topK <= 0 ? 5 : Math.min(topK, RECALL_TOP_K);

        Set<Long> visibleKbIds = resultFilter.getVisibleKbIds(userId);
        List<Long> kbIds = reqKbIds != null && !reqKbIds.isEmpty()
                ? reqKbIds.stream().filter(visibleKbIds::contains).distinct().toList()
                : new ArrayList<>(visibleKbIds);
        if (kbIds.isEmpty()) return empty(query);

        List<IntentDTO> intents = resolveIntents(kbIds, userId);
        cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy domainPolicy = resolveDomainPolicy(kbIds);
        // P0-07.5: queryAnalysis 阶段耗时(QueryAnalysisService 可能调 LLM)
        long queryAnalysisStart = System.currentTimeMillis();
        QueryAnalysis analysis = queryAnalysisService.analyze(query, history, intents, domainPolicy);
        long queryAnalysisMs = System.currentTimeMillis() - queryAnalysisStart;
        log.info("[search][queryAnalysisMs={} query={}]", queryAnalysisMs, StrUtil.maxLength(query, 60));

        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) {
            RetrievalRespVO resp = blocked(query, analysis,
                    "问题超出该知识库服务范围(未匹配到可服务的意图), 已转人工处理");
            attachRetrievalStages(resp, "ABSTAIN", queryAnalysisMs, 0, 0, 0, 0);
            return resp;
        }

        kbIds = applyScopeFilter(analysis, kbIds);
        if (kbIds.isEmpty()) {
            RetrievalRespVO resp = blocked(query, analysis,
                    "问题涉及明确地域/产品范围, 但当前可见知识库均不覆盖该范围, 已转人工处理");
            attachRetrievalStages(resp, "ABSTAIN", queryAnalysisMs, 0, 0, 0, 0);
            return resp;
        }

        String route = resolveRoute(analysis);
        if ("EXACT_METADATA".equals(route)) {
            RetrievalRespVO resp = searchExactMetadata(query, analysis, kbIds, tenantId, startMs);
            attachRetrievalStages(resp, route, queryAnalysisMs, 0, 0, 0, 0);
            return resp;
        }
        if ("EXACT_CLAIM".equals(route)) {
            RetrievalRespVO resp = searchExactClaim(query, analysis, kbIds, startMs);
            attachRetrievalStages(resp, route, queryAnalysisMs, 0, 0, 0, 0);
            return resp;
        }

        // P0-07: SCOPED_RAG 必须在 BM25/ANN 前限定目标文档(hard scope), 禁止全库检索后过滤
        List<Long> scopedDocumentIds = "SCOPED_RAG".equals(route) ? resolvePatentDocumentIds(analysis, kbIds) : null;
        if ("SCOPED_RAG".equals(route) && (scopedDocumentIds == null || scopedDocumentIds.isEmpty())) {
            // P0-07 fail closed: 明确申请号/公布号但未定位到文档 → 拒答, 不得全库 Hybrid fallback
            log.info("[search][SCOPED_RAG 文档定位失败, fail-closed, 不走全库 Hybrid: query={}]", query);
            RetrievalRespVO resp = blocked(query, analysis, "未找到对应专利文档");
            attachRetrievalStages(resp, "ABSTAIN", queryAnalysisMs, 0, 0, 0, 0);
            return resp;
        }
        RetrievalStageTimes times = new RetrievalStageTimes();
        RetrievalRespVO resp = searchHybrid(query, analysis, domainPolicy, kbIds, tenantId, topKFinal, startMs,
                scopedDocumentIds, times);
        attachRetrievalStages(resp, route, queryAnalysisMs, times.bm25Ms, times.vectorMs, times.rrfMs, times.rerankMs);
        return resp;
    }

    /** P0-09: 检索阶段耗时透传 holder(search() 统一挂载 stage 用) */
    private static final class RetrievalStageTimes {
        long bm25Ms;
        long vectorMs;
        long rrfMs;
        long rerankMs;
    }

    private RetrievalRespVO searchExactMetadata(String query, QueryAnalysis analysis, List<Long> kbIds,
                                                Long tenantId, long startMs) {
        List<Map.Entry<Long, Double>> exactHits = bm25Searcher.searchExactDocument(query, tenantId, kbIds, RECALL_TOP_K);
        // P0-10 多轮继承: 当前 query 未含申请号/公布号但 analysis 已从历史继承编号 → 用 resolvePatentDocumentIds
        // 定位目标文档已发布 chunk 作为 exact anchor(否则 BM25 无法从 "这个专利的公布号是多少" 定位文档)
        if (exactHits.isEmpty() && analysis != null
                && (StrUtil.isNotBlank(analysis.getApplicationNo()) || StrUtil.isNotBlank(analysis.getPublicationNo()))) {
            List<Long> inheritedDocIds = resolvePatentDocumentIds(analysis, kbIds);
            List<ChunkRespDTO> inheritedChunks = resultFilter.findPublishedChunksByDocuments(inheritedDocIds);
            if (!inheritedChunks.isEmpty()) {
                log.info("[searchExactMetadata][多轮继承定位文档 chunks={}, docIds={}, query={}]",
                        inheritedChunks.size(), inheritedDocIds, StrUtil.maxLength(query, 40));
                exactHits = inheritedChunks.stream()
                        .map(c -> Map.<Long, Double>entry(c.getId(), 1D))
                        .collect(Collectors.toList());
            }
        }
        List<Long> exactIds = exactHits.stream().map(Map.Entry::getKey).distinct().toList();
        RetrievalRespVO resp = baseResp(query, analysis);
        resp.getChannels().setBm25(exactIds.size());
        if (exactIds.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }
        Set<Long> published = resultFilter.filterPublished(new HashSet<>(exactIds));
        List<Long> publishedIds = exactIds.stream().filter(published::contains).toList();
        if (publishedIds.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }
        Map<Long, String> metadataMap = resultFilter.getChunkMetadatas(publishedIds);
        Map<Long, ChunkDocInfoDTO> docInfoMap = resultFilter.getChunkDocInfo(publishedIds);
        // P0-05 fail closed: 同一专利编号命中多个不同专利(编号冲突) → 拒答; 同一专利的重复导入副本允许取任一
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (Long chunkId : publishedIds) {
            String meta = metadataMap.get(chunkId);
            if (meta == null) continue;
            try {
                cn.hutool.json.JSONObject obj = JSONUtil.parseObj(meta);
                String app = obj.getStr("applicationNo");
                String pub = obj.getStr("publicationNo");
                if (StrUtil.isBlank(app) && StrUtil.isBlank(pub)) continue;
                identities.add((app != null ? "app:" + app : "") + "|" + (pub != null ? "pub:" + pub : ""));
            } catch (Exception ignored) {
                // 元数据解析失败, 不计入身份判断
            }
        }
        if (identities.size() > 1) {
            log.warn("[searchExactMetadata][同专利编号命中多个不同专利, fail-closed: identities={}, query={}]", identities, query);
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }
        Long anchorId = chooseExactMetadataAnchor(publishedIds, metadataMap);
        Map<Long, String> contentsMap = resultFilter.getChunkContents(List.of(anchorId));
        resp.setResults(List.of(buildResult(anchorId, contentsMap, docInfoMap, Map.of(anchorId, 1D), null,
                Set.of(anchorId), Set.of(), Map.of(), Map.of(), metadataMap)));
        recordTrace(query, resp, 1, startMs);
        log.info("[searchExactMetadata][PATENT EXACT_METADATA 快路径, skip embedding/vector/RRF/rerank, chunkId={}, elapsedMs={}]",
                anchorId, System.currentTimeMillis() - startMs);
        return resp;
    }

    /**
     * PATENT EXACT_CLAIM：先用申请号/公布号定位 documentId，再从 MySQL PUBLISHED PATENT_CLAIM metadata 精确取 claimNo。
     * 0 embedding / 0 vector / 0 RRF / 0 rerank。
     */
    private RetrievalRespVO searchExactClaim(String query, QueryAnalysis analysis, List<Long> kbIds, long startMs) {
        RetrievalRespVO resp = baseResp(query, analysis);
        List<Long> documentIds = resolvePatentDocumentIds(analysis, kbIds);
        if (documentIds.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }
        List<ChunkRespDTO> chunks = resultFilter.findPublishedPatentClaimChunks(documentIds, analysis.getClaimNo());
        if (chunks.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }

        // 正常数据一个 document + claimNo 只应命中一条；重复数据保守取第一条并告警。
        if (chunks.size() > 1) {
            log.warn("[searchExactClaim][精确 claim 命中 {} 条, documentIds={}, claimNo={}, 取第一条]",
                    chunks.size(), documentIds, analysis.getClaimNo());
        }
        ChunkRespDTO chunk = chunks.get(0);
        Long chunkId = chunk.getId();
        Map<Long, ChunkDocInfoDTO> docInfo = resultFilter.getChunkDocInfo(List.of(chunkId));
        RetrievalRespVO.ResultVO result = new RetrievalRespVO.ResultVO();
        result.setChunkId(chunkId);
        result.setContent(chunk.getContent());
        result.setChunkMetadata(chunk.getMetadata());
        ChunkDocInfoDTO info = docInfo.get(chunkId);
        if (info != null) {
            result.setDocumentId(info.getDocumentId());
            result.setDocumentName(info.getDocumentName());
            result.setVersionNo(info.getVersionNo());
            result.setVersionId(info.getVersionId());
        }
        result.setRrfScore(1D);
        result.setRerankScore(null);
        result.setChannels(List.of("exact"));
        resp.setResults(List.of(result));
        recordTrace(query, resp, 1, startMs);
        log.info("[searchExactClaim][PATENT EXACT_CLAIM 快路径, skip embedding/vector/RRF/rerank, documentId={}, claimNo={}, chunkId={}, elapsedMs={}]",
                result.getDocumentId(), analysis.getClaimNo(), chunkId, System.currentTimeMillis() - startMs);
        return resp;
    }

    private List<Long> resolvePatentDocumentIds(QueryAnalysis analysis, List<Long> kbIds) {
        if (analysis == null || kbIds == null || kbIds.isEmpty()
                || (StrUtil.isBlank(analysis.getApplicationNo()) && StrUtil.isBlank(analysis.getPublicationNo()))) {
            return List.of();
        }
        try {
            PatentDocumentLookupReqDTO req = new PatentDocumentLookupReqDTO();
            req.setKbIds(kbIds);
            req.setApplicationNo(analysis.getApplicationNo());
            req.setPublicationNo(analysis.getPublicationNo());
            List<Long> ids = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
            return ids == null ? List.of() : ids.stream().distinct().toList();
        } catch (Exception e) {
            log.warn("[resolvePatentDocumentIds][EXACT_CLAIM 文档定位失败, fail-closed: {}]", e.getMessage());
            return List.of();
        }
    }

    private RetrievalRespVO searchHybrid(String query, QueryAnalysis analysis,
                                         cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy domainPolicy,
                                         List<Long> kbIds, Long tenantId, int topKFinal, long startMs,
                                         List<Long> scopedDocumentIds, RetrievalStageTimes times) {
        List<String> variants = new ArrayList<>();
        variants.add(query);
        if (analysis.isSuccess()) {
            if (analysis.getRewrites() != null) variants.addAll(analysis.getRewrites());
            if (analysis.getSubQuestions() != null) variants.addAll(analysis.getSubQuestions());
        } else if (analysis.getRewrites() != null) variants.addAll(analysis.getRewrites());
        variants = variants.stream().distinct().limit(VARIANT_LIMIT).toList();

        List<Map.Entry<Long, Double>> bm25Hits = new ArrayList<>();
        long stageStart = System.currentTimeMillis();
        for (String variant : variants) {
            bm25Hits.addAll(bm25Searcher.search(variant, tenantId, kbIds, RECALL_TOP_K, scopedDocumentIds));
        }
        long bm25Ms = System.currentTimeMillis() - stageStart;
        bm25Hits = dedupMax(bm25Hits);
        Set<Long> bm25HitIds = bm25Hits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        stageStart = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> vectorHits = dedupMax(vectorSearch(variants, tenantId, kbIds, scopedDocumentIds));
        long vectorMs = System.currentTimeMillis() - stageStart;
        Set<Long> vectorHitIds = vectorHits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        stageStart = System.currentTimeMillis();
        List<Map.Entry<Long, Double>> fused = rrfMerger.merge(List.of(bm25Hits, vectorHits), RECALL_TOP_K);
        long rrfMs = System.currentTimeMillis() - stageStart;
        Map<Long, Double> rrfMap = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<Long> published = resultFilter.filterPublished(fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        List<Map.Entry<Long, Double>> candidates = fused.stream().filter(e -> published.contains(e.getKey())).toList();

        List<Long> candidateIds = candidates.stream().map(Map.Entry::getKey).toList();
        Map<Long, String> contentsMap = resultFilter.getChunkContents(candidateIds);
        List<String> contents = candidateIds.stream().map(id -> contentsMap.getOrDefault(id, "")).toList();
        Map<Long, ChunkDocInfoDTO> docInfoMap = resultFilter.getChunkDocInfo(candidateIds);
        Map<Long, String> metadataMap = resultFilter.getChunkMetadatas(candidateIds);
        Map<Long, Long> parentMap = resultFilter.getChunkParents(candidateIds);
        Set<Long> parentIds = parentMap.values().stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> parentContents = parentIds.isEmpty() ? Map.of() : resultFilter.getChunkContents(parentIds);
        Map<Long, String> truncatedParents = new java.util.HashMap<>();
        int used = 0;
        for (Map.Entry<Long, String> e : parentContents.entrySet()) {
            if (used >= 1000) break;
            String c = e.getValue() == null ? "" : e.getValue();
            String t = c.length() > 300 ? c.substring(0, 300) : c;
            truncatedParents.put(e.getKey(), t);
            used += t.length();
        }

        List<Map.Entry<Integer, Float>> reranked = new ArrayList<>();
        stageStart = System.currentTimeMillis();
        if (contents.isEmpty() || contents.stream().allMatch(StrUtil::isBlank)) {
            for (int i = 0; i < contents.size(); i++) reranked.add(Map.entry(i, 0F));
        } else {
            reranked = reranker.rerank(query, contents);
        }
        long rerankMs = System.currentTimeMillis() - stageStart;
        // P0-07.5: 输出检索阶段耗时(不含生成/验证)
        log.info("[search][bm25Ms={} vectorMs={} rrfMs={} rerankMs={} query={}]",
                bm25Ms, vectorMs, rrfMs, rerankMs, StrUtil.maxLength(query, 60));
        if (times != null) {
            times.bm25Ms = bm25Ms;
            times.vectorMs = vectorMs;
            times.rrfMs = rrfMs;
            times.rerankMs = rerankMs;
        }

        RetrievalRespVO resp = baseResp(query, analysis);
        resp.getChannels().setBm25(bm25Hits.size());
        resp.getChannels().setVector(vectorHits.size());
        resp.getChannels().setFused(fused.size());
        List<RetrievalRespVO.ResultVO> results = new ArrayList<>();
        for (Map.Entry<Integer, Float> r : reranked) {
            if (results.size() >= topKFinal) break;
            int idx = r.getKey();
            if (idx < 0 || idx >= candidates.size()) continue;
            Long chunkId = candidates.get(idx).getKey();
            // P0-07 Evidence gate: SCOPED_RAG 候选必须属于目标文档, 候选阶段即不允许串文档
            if (scopedDocumentIds != null && !scopedDocumentIds.isEmpty()) {
                ChunkDocInfoDTO info = docInfoMap.get(chunkId);
                if (info == null || info.getDocumentId() == null || !scopedDocumentIds.contains(info.getDocumentId())) continue;
            }
            results.add(buildResult(chunkId, contentsMap, docInfoMap, rrfMap, r.getValue(), bm25HitIds, vectorHitIds,
                    parentMap, truncatedParents, metadataMap));
        }
        resp.setResults(results);
        recordTrace(query, resp, variants.size(), startMs);

        List<String> questionProducts = analysis.getProducts() == null ? List.of() : analysis.getProducts();
        if (!domainPolicy.enableProductGate()) questionProducts = List.of();
        boolean docInfoUnavailable = !results.isEmpty() && (docInfoMap == null || docInfoMap.isEmpty());
        Set<String> docProducts = docInfoUnavailable ? Set.of() : collectDocProducts(results, docInfoMap);
        boolean productMatch = docInfoUnavailable || questionProducts.isEmpty()
                || questionProducts.stream().anyMatch(p -> docProducts.stream().anyMatch(dp -> dp.contains(p) || p.contains(dp)));
        if (!productMatch) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("问题涉及产品「" + String.join("、", questionProducts)
                    + "」, 现有资料仅覆盖「" + (docProducts.isEmpty() ? "无" : String.join("、", docProducts))
                    + "」, 无法确认其政策, 拒绝作答");
        }
        return resp;
    }

    private RetrievalRespVO baseResp(String query, QueryAnalysis analysis) {
        RetrievalRespVO resp = new RetrievalRespVO();
        resp.setQuery(query);
        resp.setAnalysis(buildAnalysis(analysis));
        resp.setChannels(new RetrievalRespVO.ChannelStatVO());
        resp.setAnswer(null);
        return resp;
    }

    private RetrievalRespVO empty(String query) {
        RetrievalRespVO resp = new RetrievalRespVO();
        resp.setQuery(query);
        resp.setAnalysis(new RetrievalRespVO.AnalysisVO());
        resp.setChannels(new RetrievalRespVO.ChannelStatVO());
        resp.setResults(List.of());
        return resp;
    }

    private RetrievalRespVO blocked(String query, QueryAnalysis analysis, String reason) {
        RetrievalRespVO resp = baseResp(query, analysis);
        resp.setAnswerBlocked(true);
        resp.setAnswerReason(reason);
        resp.setResults(List.of());
        return resp;
    }

    private Long chooseExactMetadataAnchor(List<Long> ids, Map<Long, String> metadataMap) {
        for (Long id : ids) {
            String metadata = metadataMap.get(id);
            if (StrUtil.isBlank(metadata)) continue;
            try {
                if ("BIBLIOGRAPHIC".equalsIgnoreCase(JSONUtil.parseObj(metadata).getStr("sectionType"))) return id;
            } catch (Exception ignore) {}
        }
        return ids.get(0);
    }

    private cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy resolveDomainPolicy(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return domainPolicyRegistry.get("GENERAL");
        try {
            Map<Long, String> domains = knowledgeApi.getKbDomainCodes(kbIds).getCheckedData();
            if (domains == null || domains.isEmpty()) return domainPolicyRegistry.get("GENERAL");
            Set<String> unique = new HashSet<>(domains.values());
            if (unique.size() == 1) return domainPolicyRegistry.get(unique.iterator().next());
            if (unique.size() > 1) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                    1_005_000_100, "一次只能检索同一领域(如专利)的知识库, 请先选择单个领域的知识库");
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[resolveDomainPolicy][领域解析失败, 回退 GENERAL: {}]", e.getMessage());
        }
        return domainPolicyRegistry.get("GENERAL");
    }

    private List<Long> applyScopeFilter(QueryAnalysis analysis, List<Long> kbIds) {
        boolean hasProvince = StrUtil.isNotBlank(analysis.getProvince());
        boolean hasCity = StrUtil.isNotBlank(analysis.getCity());
        boolean hasProduct = analysis.getProducts() != null && !analysis.getProducts().isEmpty();
        if ((!hasProvince && !hasCity && !hasProduct) || kbIds == null || kbIds.isEmpty()) return kbIds;
        try {
            Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>> scopes =
                    knowledgeApi.getKbScopes(kbIds).getCheckedData();
            if (scopes == null || scopes.isEmpty()) return kbIds;
            List<Long> filtered = new ArrayList<>();
            for (Long kbId : kbIds) {
                List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO> kbScopes = scopes.get(kbId);
                if (kbScopes == null || kbScopes.isEmpty() || scopeMatches(analysis, kbScopes)) filtered.add(kbId);
            }
            return filtered;
        } catch (Exception e) {
            log.warn("[applyScopeFilter][scope RPC 失败, 降级不过滤: {}]", e.getMessage());
            return kbIds;
        }
    }

    private boolean scopeMatches(QueryAnalysis analysis,
                                 List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO> kbScopes) {
        boolean regionOk = true;
        if (StrUtil.isNotBlank(analysis.getCity())) {
            regionOk = kbScopes.stream().anyMatch(s -> "CITY".equals(s.getScopeType())
                    && s.getScopeCode() != null && s.getScopeCode().contains(analysis.getCity()));
        } else if (StrUtil.isNotBlank(analysis.getProvince())) {
            regionOk = kbScopes.stream().anyMatch(s -> ("PROVINCE".equals(s.getScopeType()) || "CITY".equals(s.getScopeType()))
                    && s.getScopeCode() != null && s.getScopeCode().contains(analysis.getProvince()));
        }
        boolean productOk = true;
        if (analysis.getProducts() != null && !analysis.getProducts().isEmpty()) {
            List<String> productScopes = kbScopes.stream().filter(s -> "PRODUCT".equals(s.getScopeType()))
                    .map(cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO::getScopeCode)
                    .filter(java.util.Objects::nonNull).toList();
            if (!productScopes.isEmpty()) productOk = analysis.getProducts().stream()
                    .anyMatch(p -> productScopes.stream().anyMatch(sp -> sp.contains(p) || p.contains(sp)));
        }
        return regionOk && productOk;
    }

    List<IntentDTO> resolveIntents(List<Long> kbIds) {
        return resolveIntents(kbIds, SecurityFrameworkUtils.getLoginUserId());
    }

    List<IntentDTO> resolveIntents(List<Long> kbIds, Long userId) {
        List<Long> ids = (kbIds != null && !kbIds.isEmpty()) ? kbIds
                : (userId == null ? List.of() : new ArrayList<>(resultFilter.getVisibleKbIds(userId)));
        if (ids.isEmpty()) return List.of();
        Map<String, IntentDTO> byName = new LinkedHashMap<>();
        for (Long kbId : ids.stream().distinct().toList()) {
            try {
                List<IntentDTO> list = knowledgeApi.getKbIntents(kbId).getCheckedData();
                if (list == null) continue;
                for (IntentDTO intent : list) if (intent != null && StrUtil.isNotBlank(intent.getName()))
                    byName.putIfAbsent(intent.getName(), intent);
            } catch (Exception e) {
                log.warn("[resolveIntents][知识库 {} 意图获取失败: {}]", kbId, e.getMessage());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private Set<String> collectDocProducts(List<RetrievalRespVO.ResultVO> results,
                                           Map<Long, ChunkDocInfoDTO> docInfoMap) {
        Set<String> products = new HashSet<>();
        for (RetrievalRespVO.ResultVO r : results) {
            ChunkDocInfoDTO info = docInfoMap.get(r.getChunkId());
            if (info != null && StrUtil.isNotBlank(info.getProducts())) {
                for (String p : StrUtil.split(info.getProducts(), ',')) if (StrUtil.isNotBlank(p)) products.add(p.trim());
            }
        }
        return products;
    }

    private List<Map.Entry<Long, Double>> vectorSearch(List<String> variants, Long tenantId, List<Long> kbIds,
                                                       List<Long> documentIds) {
        try {
            long embedStart = System.currentTimeMillis();
            List<List<Float>> vectors = modelApi.embedding(variants).getCheckedData();
            long embeddingMs = System.currentTimeMillis() - embedStart;
            log.info("[vectorSearch][embeddingMs={} variants={}]", embeddingMs, variants.size());
            if (vectors == null || vectors.isEmpty()) return List.of();
            return vectorSearcher.search(vectors, tenantId, kbIds, RECALL_TOP_K, documentIds);
        } catch (Exception e) {
            log.warn("[vectorSearch][向量检索失败, 跳过向量通道: {}]", e.getMessage());
            return List.of();
        }
    }

    private List<Map.Entry<Long, Double>> dedupMax(List<Map.Entry<Long, Double>> list) {
        Map<Long, Double> map = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : list) map.merge(e.getKey(), e.getValue(), Math::max);
        return new ArrayList<>(map.entrySet());
    }

    private RetrievalRespVO.AnalysisVO buildAnalysis(QueryAnalysis analysis) {
        RetrievalRespVO.AnalysisVO vo = new RetrievalRespVO.AnalysisVO();        vo.setIntent(analysis.getIntent());
        vo.setEntities(analysis.getEntities());
        vo.setProducts(analysis.getProducts());
        vo.setRewrites(analysis.getRewrites());
        vo.setSubQuestions(analysis.getSubQuestions());
        vo.setSuccess(analysis.isSuccess());
        vo.setRoute(resolveRoute(analysis));
        return vo;
    }

    private String resolveRoute(QueryAnalysis analysis) {
        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) return "ABSTAIN";
        if (StrUtil.isNotBlank(analysis.getRoute())) return analysis.getRoute();
        // 地域/产品/文档等 Scope 过滤后仍属单文档范围检索, 不新增 SCOPE_FILTER_* 路由
        if (StrUtil.isNotBlank(analysis.getProvince()) || StrUtil.isNotBlank(analysis.getCity())) return "SCOPED_RAG";
        return "HYBRID_RAG";
    }

    /**
     * P0-09: 按路由构建检索阶段时序(未执行阶段标 SKIPPED), 写入分析 DTO 供上层汇聚统一 Trace。
     * 仅记录阶段/状态/耗时摘要, 不含敏感内容。
     */
    private void attachRetrievalStages(RetrievalRespVO resp, String route, long queryAnalysisMs,
                                       long bm25Ms, long vectorMs, long rrfMs, long rerankMs) {
        if (resp == null || resp.getAnalysis() == null) return;
        List<cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        stages.add(traceStage("ANALYZE", ++seq, queryAnalysisMs, null, null));
        stages.add(traceStage("ROUTE", ++seq, 0, null, null));
        if ("EXACT_METADATA".equals(route)) {
            stages.add(traceStage("DOC_LOOKUP", ++seq, 0, null, null));
            stages.add(traceSkip("VECTOR", ++seq));
            stages.add(traceSkip("RERANK", ++seq));
            stages.add(traceSkip("GENERATE", ++seq));
            stages.add(traceSkip("VERIFY", ++seq));
        } else if ("EXACT_CLAIM".equals(route)) {
            stages.add(traceStage("DOC_LOOKUP", ++seq, 0, null, null));
            stages.add(traceStage("CLAIM_LOOKUP", ++seq, 0, null, null));
            stages.add(traceSkip("LLM", ++seq));
        } else if ("ABSTAIN".equals(route)) {
            // OUT_OF_SCOPE/阻断: 分析后即中止, 检索阶段未执行
        } else {
            // SCOPED_RAG / HYBRID_RAG
            stages.add(traceStage("SCOPE_FILTER", ++seq, 0, null, null));
            stages.add(traceStage("BM25", ++seq, bm25Ms, null, null));
            stages.add(traceStage("VECTOR", ++seq, vectorMs, null, null));
            stages.add(traceStage("FUSION", ++seq, rrfMs, null, null));
            stages.add(traceStage("RERANK", ++seq, rerankMs, null, null));
            stages.add(traceStage("DOC_LOOKUP", ++seq, 0, null, null));
        }
        resp.getAnalysis().setStages(stages);
    }

    private cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO traceStage(String stage, int seq,
            long elapsedMs, String errorCode, String errorMessage) {
        cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO dto =
                new cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO();
        dto.setStage(stage);
        dto.setSeq(seq);
        dto.setStatus(errorCode != null ? "FAILED" : "SUCCEEDED");
        dto.setElapsedMs(elapsedMs);
        dto.setSkipped(false);
        dto.setErrorCode(errorCode);
        dto.setErrorMessage(errorMessage);
        return dto;
    }

    private cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO traceSkip(String stage, int seq) {
        cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO dto =
                new cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO();
        dto.setStage(stage);
        dto.setSeq(seq);
        dto.setStatus("SKIPPED");
        dto.setElapsedMs(0L);
        dto.setSkipped(true);
        return dto;
    }

    private void recordTrace(String query, RetrievalRespVO resp, int variantCount, long startMs) {
        try {
            cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.RetrievalTraceDO t =
                    new cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.RetrievalTraceDO();
            t.setTraceId(java.util.UUID.randomUUID().toString().replace("-", ""));
            t.setQuery(StrUtil.maxLength(query, 500));
            t.setRoute(resp.getAnalysis() != null ? resp.getAnalysis().getRoute() : null);
            t.setIntent(resp.getAnalysis() != null ? resp.getAnalysis().getIntent() : null);
            t.setVariantCount(variantCount);
            t.setBm25Hits(resp.getChannels() != null ? resp.getChannels().getBm25() : 0);
            t.setVectorHits(resp.getChannels() != null ? resp.getChannels().getVector() : 0);
            t.setFused(resp.getChannels() != null ? resp.getChannels().getFused() : 0);
            t.setResultCount(resp.getResults() != null ? resp.getResults().size() : 0);
            t.setElapsedMs((int) (System.currentTimeMillis() - startMs));
            t.setBlocked(Boolean.TRUE.equals(resp.getAnswerBlocked()));
            retrievalTraceMapper.insert(t);
        } catch (Exception e) {
            log.warn("[recordTrace][检索追踪落库失败: {}]", e.getMessage());
        }
    }

    private RetrievalRespVO.ResultVO buildResult(Long chunkId, Map<Long, String> contentsMap,
            Map<Long, ChunkDocInfoDTO> docInfoMap, Map<Long, Double> rrfMap, Float rerankScore,
            Set<Long> bm25HitIds, Set<Long> vectorHitIds, Map<Long, Long> parentMap,
            Map<Long, String> parentContents, Map<Long, String> metadataMap) {
        RetrievalRespVO.ResultVO vo = new RetrievalRespVO.ResultVO();
        vo.setChunkId(chunkId);
        vo.setContent(contentsMap.getOrDefault(chunkId, ""));
        ChunkDocInfoDTO docInfo = docInfoMap.get(chunkId);
        if (docInfo != null) {
            vo.setDocumentId(docInfo.getDocumentId());
            vo.setDocumentName(docInfo.getDocumentName());
            vo.setVersionNo(docInfo.getVersionNo());
            vo.setVersionId(docInfo.getVersionId());
        }
        vo.setChunkMetadata(metadataMap.get(chunkId));
        vo.setRrfScore(rrfMap.get(chunkId));
        vo.setRerankScore(rerankScore);
        Long parentId = parentMap.get(chunkId);
        if (parentId != null) {
            vo.setContextChunkId(parentId);
            vo.setContextContent(parentContents.get(parentId));
        }
        List<String> channels = new ArrayList<>();
        if (bm25HitIds.contains(chunkId)) channels.add("bm25");
        if (vectorHitIds.contains(chunkId)) channels.add("vector");
        vo.setChannels(channels);
        return vo;
    }
}
