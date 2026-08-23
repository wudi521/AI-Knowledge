package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
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

/**
 * 检索编排: 查询分析 → Query Planner → Exact/Scoped/Hybrid 检索 → 响应。
 * <p>
 * EXACT_METADATA 已走确定性快路径: 精确定位 documentId 后只取一个已发布来源锚点，
 * 跳过 embedding / vector / RRF / rerank；答案由 Evidence 层直接读取 chunk metadata。
 */
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
                SecurityFrameworkUtils.getLoginUser().getTenantId(),
                SecurityFrameworkUtils.getLoginUserId());
    }

    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId) {
        return search(query, reqKbIds, topK, tenantId, userId, null);
    }

    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId,
                                  List<ChatTurnDTO> history) {
        long startMs = System.currentTimeMillis();
        int topKFinal = topK == null || topK <= 0 ? 5 : Math.min(topK, RECALL_TOP_K);

        // 1. 权限前置
        Set<Long> visibleKbIds = resultFilter.getVisibleKbIds(userId);
        List<Long> kbIds = reqKbIds != null && !reqKbIds.isEmpty()
                ? reqKbIds.stream().filter(visibleKbIds::contains).distinct().collect(Collectors.toList())
                : new ArrayList<>(visibleKbIds);
        if (kbIds.isEmpty()) {
            log.warn("[search][query={} 无可见知识库, 返回空]", query);
            RetrievalRespVO empty = new RetrievalRespVO();
            empty.setQuery(query);
            empty.setAnalysis(new RetrievalRespVO.AnalysisVO());
            empty.setChannels(new RetrievalRespVO.ChannelStatVO());
            empty.setResults(List.of());
            return empty;
        }

        // 2. 领域 + 查询分析。PATENT EXACT_METADATA 在 QueryAnalysisService 内会规则短路 LLM。
        List<IntentDTO> intents = resolveIntents(kbIds, userId);
        cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy domainPolicy = resolveDomainPolicy(kbIds);
        QueryAnalysis analysis = queryAnalysisService.analyze(query, history, intents, domainPolicy);

        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) {
            log.info("[search][query={} 意图 OUT_OF_SCOPE, 跳过检索并阻断作答]", query);
            RetrievalRespVO blocked = new RetrievalRespVO();
            blocked.setQuery(query);
            blocked.setAnalysis(buildAnalysis(analysis));
            blocked.setChannels(new RetrievalRespVO.ChannelStatVO());
            blocked.setAnswerBlocked(true);
            blocked.setAnswerReason("问题超出该知识库服务范围(未匹配到可服务的意图), 已转人工处理");
            blocked.setResults(List.of());
            blocked.setAnswer(null);
            return blocked;
        }

        // 3. 通用 scope 过滤
        List<Long> scopedKbIds = applyScopeFilter(analysis, kbIds);
        if (scopedKbIds.isEmpty()) {
            log.warn("[search][query={} 地域/产品范围过滤后无可用知识库, 转人工]", query);
            RetrievalRespVO blocked = new RetrievalRespVO();
            blocked.setQuery(query);
            blocked.setAnalysis(buildAnalysis(analysis));
            blocked.setChannels(new RetrievalRespVO.ChannelStatVO());
            blocked.setAnswerBlocked(true);
            blocked.setAnswerReason("问题涉及明确地域/产品范围, 但当前可见知识库均不覆盖该范围, 已转人工处理");
            blocked.setResults(List.of());
            blocked.setAnswer(null);
            return blocked;
        }
        kbIds = scopedKbIds;

        // 4. Query Planner: EXACT_METADATA 直接走精确文档来源锚点，不进入通用 Hybrid RAG。
        if ("EXACT_METADATA".equals(resolveRoute(analysis))) {
            return searchExactMetadata(query, analysis, kbIds, tenantId, startMs);
        }

        // 5. 通用/Scoped/ExactClaim 当前仍复用 Hybrid 主链；PATENT 精确编号由 BM25 + Reranker hard filter 保证不串文档。
        List<String> variants = new ArrayList<>();
        variants.add(query);
        if (analysis.isSuccess()) {
            if (analysis.getRewrites() != null) variants.addAll(analysis.getRewrites());
            if (analysis.getSubQuestions() != null) variants.addAll(analysis.getSubQuestions());
        } else if (analysis.getRewrites() != null && !analysis.getRewrites().isEmpty()) {
            variants.addAll(analysis.getRewrites());
        }
        variants = variants.stream().distinct().limit(VARIANT_LIMIT).collect(Collectors.toList());

        List<Map.Entry<Long, Double>> bm25Hits = new ArrayList<>();
        for (String variant : variants) {
            bm25Hits.addAll(bm25Searcher.search(variant, tenantId, kbIds, RECALL_TOP_K));
        }
        bm25Hits = dedupMax(bm25Hits);
        Set<Long> bm25HitIds = bm25Hits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        List<Map.Entry<Long, Double>> vectorHits = dedupMax(vectorSearch(variants, tenantId, kbIds));
        Set<Long> vectorHitIds = vectorHits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        List<Map.Entry<Long, Double>> fused = rrfMerger.merge(List.of(bm25Hits, vectorHits), RECALL_TOP_K);
        Map<Long, Double> rrfMap = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Set<Long> published = resultFilter.filterPublished(
                fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        List<Map.Entry<Long, Double>> candidates = fused.stream()
                .filter(e -> published.contains(e.getKey()))
                .collect(Collectors.toList());

        List<Long> candidateIds = candidates.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        Map<Long, String> contentsMap = resultFilter.getChunkContents(candidateIds);
        List<String> contents = candidateIds.stream().map(id -> contentsMap.getOrDefault(id, "")).toList();
        Map<Long, ChunkDocInfoDTO> docInfoMap = resultFilter.getChunkDocInfo(candidateIds);
        Map<Long, String> metadataMap = resultFilter.getChunkMetadatas(candidateIds);

        Map<Long, Long> parentMap = resultFilter.getChunkParents(candidateIds);
        Set<Long> parentIds = parentMap.values().stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> parentContents = parentIds.isEmpty() ? Map.of() : resultFilter.getChunkContents(parentIds);
        int contextBudget = 1000;
        int contextUsed = 0;
        Map<Long, String> truncatedParents = new java.util.HashMap<>();
        for (Map.Entry<Long, String> e : parentContents.entrySet()) {
            if (contextUsed >= contextBudget) break;
            String content = e.getValue() == null ? "" : e.getValue();
            String truncated = content.length() > 300 ? content.substring(0, 300) : content;
            truncatedParents.put(e.getKey(), truncated);
            contextUsed += truncated.length();
        }

        List<Map.Entry<Integer, Float>> reranked;
        boolean allBlank = contents.isEmpty() || contents.stream().allMatch(StrUtil::isBlank);
        if (allBlank) {
            reranked = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) reranked.add(Map.entry(i, 0F));
        } else {
            reranked = reranker.rerank(query, contents);
        }

        RetrievalRespVO resp = new RetrievalRespVO();
        resp.setQuery(query);
        resp.setAnalysis(buildAnalysis(analysis));
        RetrievalRespVO.ChannelStatVO stat = new RetrievalRespVO.ChannelStatVO();
        stat.setBm25(bm25Hits.size());
        stat.setVector(vectorHits.size());
        stat.setFused(fused.size());
        resp.setChannels(stat);

        List<RetrievalRespVO.ResultVO> results = new ArrayList<>();
        for (Map.Entry<Integer, Float> r : reranked) {
            if (results.size() >= topKFinal) break;
            int idx = r.getKey();
            if (idx < 0 || idx >= candidates.size()) continue;
            Long chunkId = candidates.get(idx).getKey();
            results.add(buildResult(chunkId, contentsMap, docInfoMap, rrfMap, r.getValue(),
                    bm25HitIds, vectorHitIds, parentMap, truncatedParents, metadataMap));
        }
        resp.setResults(results);
        recordTrace(query, resp, variants.size(), startMs);

        // 产品门禁仅 GENERAL 等启用领域执行；PATENT 关闭。
        List<String> questionProducts = analysis.getProducts() == null ? List.of() : analysis.getProducts();
        if (!domainPolicy.enableProductGate()) questionProducts = List.of();
        boolean docInfoUnavailable = !results.isEmpty() && (docInfoMap == null || docInfoMap.isEmpty());
        if (docInfoUnavailable) {
            log.warn("[search][query={} 文档信息获取失败, 跳过产品/品牌一致性门禁]", query);
        }
        Set<String> docProducts = docInfoUnavailable ? Set.of() : collectDocProducts(results, docInfoMap);
        boolean productMatch = docInfoUnavailable || questionProducts.isEmpty()
                || questionProducts.stream().anyMatch(p ->
                docProducts.stream().anyMatch(dp -> dp.contains(p) || p.contains(dp)));
        if (!productMatch) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("问题涉及产品「" + String.join("、", questionProducts)
                    + "」, 现有资料仅覆盖「" + (docProducts.isEmpty() ? "无" : String.join("、", docProducts))
                    + "」, 无法确认其政策, 拒绝作答");
        }
        resp.setAnswer(null);
        return resp;
    }

    /**
     * PATENT EXACT_METADATA 快路径。
     * <p>
     * 只需要一个已发布 chunk 作为 Citation 锚点，因为整份专利的著录字段已复制到每个 chunk metadata。
     * 这里不做 embedding/vector/RRF/rerank，真正做到“结构化事实不走语义检索”。
     */
    private RetrievalRespVO searchExactMetadata(String query, QueryAnalysis analysis, List<Long> kbIds,
                                                Long tenantId, long startMs) {
        List<Map.Entry<Long, Double>> exactHits = bm25Searcher.searchExactDocument(query, tenantId, kbIds, RECALL_TOP_K);
        List<Long> exactIds = exactHits.stream().map(Map.Entry::getKey).distinct().toList();

        RetrievalRespVO resp = new RetrievalRespVO();
        resp.setQuery(query);
        resp.setAnalysis(buildAnalysis(analysis));
        RetrievalRespVO.ChannelStatVO stat = new RetrievalRespVO.ChannelStatVO();
        stat.setBm25(exactIds.size());
        stat.setVector(0);
        stat.setFused(0);
        resp.setChannels(stat);
        resp.setAnswer(null);

        if (exactIds.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            log.info("[searchExactMetadata][query={} 未找到精确专利文档来源锚点]", query);
            return resp;
        }

        // MySQL publish 状态再次校验，避免 ES 残留索引被当成有效来源。
        Set<Long> published = resultFilter.filterPublished(new HashSet<>(exactIds));
        List<Long> publishedIds = exactIds.stream().filter(published::contains).toList();
        if (publishedIds.isEmpty()) {
            resp.setResults(List.of());
            recordTrace(query, resp, 1, startMs);
            return resp;
        }

        Map<Long, String> metadataMap = resultFilter.getChunkMetadatas(publishedIds);
        Long anchorId = chooseExactMetadataAnchor(publishedIds, metadataMap);
        Map<Long, String> contentsMap = resultFilter.getChunkContents(List.of(anchorId));
        Map<Long, ChunkDocInfoDTO> docInfoMap = resultFilter.getChunkDocInfo(List.of(anchorId));

        RetrievalRespVO.ResultVO result = buildResult(
                anchorId,
                contentsMap,
                docInfoMap,
                Map.of(anchorId, 1D),
                null,
                Set.of(anchorId),
                Set.of(),
                Map.of(),
                Map.of(),
                metadataMap);
        resp.setResults(List.of(result));
        recordTrace(query, resp, 1, startMs);
        log.info("[searchExactMetadata][PATENT EXACT_METADATA 快路径, skip embedding/vector/RRF/rerank, chunkId={}, elapsedMs={}]",
                anchorId, System.currentTimeMillis() - startMs);
        return resp;
    }

    private Long chooseExactMetadataAnchor(List<Long> ids, Map<Long, String> metadataMap) {
        for (Long id : ids) {
            String metadata = metadataMap.get(id);
            if (StrUtil.isBlank(metadata)) continue;
            try {
                if ("BIBLIOGRAPHIC".equalsIgnoreCase(JSONUtil.parseObj(metadata).getStr("sectionType"))) return id;
            } catch (Exception ignore) {
                // 历史脏 metadata 不阻断，回退第一条已发布 chunk。
            }
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
            if (unique.size() > 1) {
                log.warn("[resolveDomainPolicy][跨领域知识库, 拒绝检索: {}]", domains);
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                        1_005_000_100, "一次只能检索同一领域(如专利)的知识库, 请先选择单个领域的知识库");
            }
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

        Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>> scopes;
        try {
            scopes = knowledgeApi.getKbScopes(kbIds).getCheckedData();
        } catch (Exception e) {
            log.warn("[applyScopeFilter][scope RPC 失败, 降级不过滤: {}]", e.getMessage());
            return kbIds;
        }
        if (scopes == null || scopes.isEmpty()) return kbIds;

        List<Long> filtered = new ArrayList<>();
        for (Long kbId : kbIds) {
            List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO> kbScopes = scopes.get(kbId);
            if (kbScopes == null || kbScopes.isEmpty() || scopeMatches(analysis, kbScopes)) filtered.add(kbId);
        }
        return filtered;
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
            List<String> productScopes = kbScopes.stream()
                    .filter(s -> "PRODUCT".equals(s.getScopeType()))
                    .map(cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO::getScopeCode)
                    .filter(java.util.Objects::nonNull).toList();
            if (!productScopes.isEmpty()) {
                productOk = analysis.getProducts().stream().anyMatch(p -> productScopes.stream()
                        .anyMatch(sp -> sp.contains(p) || p.contains(sp)));
            }
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

        List<Long> distinctIds = ids.stream().distinct().toList();
        Map<String, IntentDTO> byName = new LinkedHashMap<>();
        for (Long kbId : distinctIds) {
            try {
                List<IntentDTO> intents = knowledgeApi.getKbIntents(kbId).getCheckedData();
                if (intents == null) continue;
                for (IntentDTO intent : intents) {
                    if (intent != null && StrUtil.isNotBlank(intent.getName())) byName.putIfAbsent(intent.getName(), intent);
                }
            } catch (Exception e) {
                log.warn("[resolveIntents][知识库 {} 意图获取失败, 跳过该库: {}]", kbId, e.getMessage());
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
                for (String p : StrUtil.split(info.getProducts(), ',')) {
                    if (StrUtil.isNotBlank(p)) products.add(p.trim());
                }
            }
        }
        return products;
    }

    private List<Map.Entry<Long, Double>> vectorSearch(List<String> variants, Long tenantId, List<Long> kbIds) {
        try {
            List<List<Float>> vectors = modelApi.embedding(variants).getCheckedData();
            if (vectors == null || vectors.isEmpty()) return List.of();
            return vectorSearcher.search(vectors, tenantId, kbIds, RECALL_TOP_K);
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
        RetrievalRespVO.AnalysisVO vo = new RetrievalRespVO.AnalysisVO();
        vo.setIntent(analysis.getIntent());
        vo.setEntities(analysis.getEntities());
        vo.setProducts(analysis.getProducts());
        vo.setRewrites(analysis.getRewrites());
        vo.setSubQuestions(analysis.getSubQuestions());
        vo.setSuccess(analysis.isSuccess());
        vo.setRoute(resolveRoute(analysis));
        return vo;
    }

    /**
     * Query Planner 路由：领域确定性分析结果优先；GENERAL 未提供 route 时再按旧逻辑回退。
     */
    private String resolveRoute(QueryAnalysis analysis) {
        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) return "ABSTAIN";
        if (StrUtil.isNotBlank(analysis.getRoute())) return analysis.getRoute();
        if (StrUtil.isNotBlank(analysis.getProvince()) || StrUtil.isNotBlank(analysis.getCity())) {
            return "SCOPE_FILTER_HYBRID_RAG";
        }
        return "HYBRID_RAG";
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
            Set<Long> bm25HitIds, Set<Long> vectorHitIds,
            Map<Long, Long> parentMap, Map<Long, String> parentContents,
            Map<Long, String> metadataMap) {
        RetrievalRespVO.ResultVO vo = new RetrievalRespVO.ResultVO();
        vo.setChunkId(chunkId);
        vo.setContent(contentsMap.getOrDefault(chunkId, ""));
        ChunkDocInfoDTO docInfo = docInfoMap.get(chunkId);
        if (docInfo != null) {
            vo.setDocumentId(docInfo.getDocumentId());
            vo.setDocumentName(docInfo.getDocumentName());
            vo.setVersionNo(docInfo.getVersionNo());
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
