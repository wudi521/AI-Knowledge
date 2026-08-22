package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
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
 * 检索编排: 语义理解/改写 → 双通道召回(BM25 + 向量) → RRF 融合 → 权限/已发布过滤 → 重排 → 响应
 * <p>
 * 降级原则(业务效果优先): 单环节失败不阻断主链路, 允许"空手而归"的最小集是空结果而非报错
 * <p>
 * Task 3 动态意图: 检索前按 kbIds 拉取知识库意图集注入查询分析(意图 = 知识库意图名 | OUT_OF_SCOPE);
 * RPC 失败/无意图时回退固定枚举, 不阻断主链路。
 */
@Slf4j
@Service
public class SearchService {

    /** 召回/重排候选上限(控制 LLM 打分成本) */
    private static final int RECALL_TOP_K = 20;
    /** 检索变体上限 */
    private static final int VARIANT_LIMIT = 6;

    @Resource
    private QueryAnalysisService queryAnalysisService;
    @Resource
    private KnowledgeApi knowledgeApi;
    @Resource
    private Bm25Searcher bm25Searcher;
    @Resource
    private VectorSearcher vectorSearcher;
    @Resource
    private RrfMerger rrfMerger;
    @Resource
    private Reranker reranker;
    @Resource
    private ResultFilter resultFilter;
    @Resource
    private ModelApi modelApi;
    @Resource
    private cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicyRegistry domainPolicyRegistry;
    @Resource
    private cn.iocoder.yudao.module.retrieval.dal.mysql.trace.RetrievalTraceMapper retrievalTraceMapper;

    public RetrievalRespVO search(RetrievalReqVO req) {
        return search(req.getQuery(), req.getKbIds(), req.getTopK(),
                SecurityFrameworkUtils.getLoginUser().getTenantId(),
                SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 检索(显式租户/用户版本, 供 RPC 调用: 无登录态, 租户/权限由调用方传递; 单轮, 无上下文)
     */
    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId) {
        return search(query, reqKbIds, topK, tenantId, userId, null);
    }

    /**
     * 检索(显式租户/用户版本, 供 RPC 调用: 无登录态, 租户/权限由调用方传递; 支持多轮上下文)
     *
     * @param history 上下文轮次(可选, 空/ null = 单轮; 已接入查询分析做历史消歧)
     */
    public RetrievalRespVO search(String query, List<Long> reqKbIds, Integer topK, Long tenantId, Long userId,
                                  List<ChatTurnDTO> history) {
        long startMs = System.currentTimeMillis(); // F5 检索追踪耗时
        // 1. 参数归一: topK 默认 5, 上限 20
        int topKFinal = topK == null || topK <= 0 ? 5 : Math.min(topK, RECALL_TOP_K);

        // 2. 权限前置: 可见知识库计算 + 空集短路(在 LLM 调用之前, 避免无效消耗且防越权泄露)
        Set<Long> visibleKbIds = resultFilter.getVisibleKbIds(userId);
        List<Long> kbIds = reqKbIds != null && !reqKbIds.isEmpty()
                ? reqKbIds.stream().filter(visibleKbIds::contains).distinct().collect(Collectors.toList())
                : new ArrayList<>(visibleKbIds);
        // ⚠️ 权限边界: 交集为空(请求只含不可见知识库 / 可见集获取失败)必须短路返回空,
        //    否则双检索器把空 kbIds 当"不限", 泄露不可见知识库内容(越权 0 容忍)
        if (kbIds.isEmpty()) {
            log.warn("[search][query={} 无可见知识库, 返回空]", query);
            RetrievalRespVO empty = new RetrievalRespVO();
            empty.setQuery(query);
            empty.setAnalysis(new RetrievalRespVO.AnalysisVO());
            empty.setChannels(new RetrievalRespVO.ChannelStatVO());
            empty.setResults(List.of());
            return empty;
        }

        // 3. 语义理解/改写/拆解: 变体 = 原句 + (改写 + 子问题), 去重限 6
        //    Task 2: history 融入查询分析(指代展开/实体继承); LLM 失败时规则兜底改写仍参与召回
        //    Task 3: 按 kbIds 解析知识库意图集注入动态分类; 意图为空(RPC 失败/知识库无意图)回退固定枚举
        List<IntentDTO> intents = resolveIntents(kbIds, userId);
        cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy domainPolicy = resolveDomainPolicy(kbIds);
        QueryAnalysis analysis = queryAnalysisService.analyze(query, history, intents, domainPolicy);
        List<String> variants = new ArrayList<>();
        variants.add(query);
        if (analysis.isSuccess()) {
            if (analysis.getRewrites() != null) {
                variants.addAll(analysis.getRewrites());
            }
            if (analysis.getSubQuestions() != null) {
                variants.addAll(analysis.getSubQuestions());
            }
        } else if (analysis.getRewrites() != null && !analysis.getRewrites().isEmpty()) {
            // 规则兜底(LLM 失败 + 历史合并): 仅补充改写参与召回, 无实体/子问题, 不提升 success
            variants.addAll(analysis.getRewrites());
        }
        variants = variants.stream().distinct().limit(VARIANT_LIMIT).collect(Collectors.toList());

        // 3.5 超范围意图短路(Task 4): 动态意图解析为 OUT_OF_SCOPE 时不检索不硬答, 拒绝作答并转人工。
        //     仅知识库意图集路径(有 kbIds 意图)会产生 OUT_OF_SCOPE; 无意图集回退路径恒为固定枚举, 不受影响。
        //     置于所有检索调用(BM25/向量/重排/LLM)之前, 避免无效消耗。
        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) {
            log.info("[search][query={} 意图 OUT_OF_SCOPE, 跳过检索并阻断作答, 转人工]", query);
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

        // 3.6 D2 scope 硬过滤: 查询命中省市/产品 slot 时, 有 scope 配置的知识库必须匹配
        //     (精确城市>省级; 无 scope 配置的知识库不受影响, 兼容现状; scope RPC 失败降级不过滤+告警)
        java.util.List<Long> scopedKbIds = applyScopeFilter(analysis, kbIds);
        if (scopedKbIds.isEmpty()) {
            log.warn("[search][query={} 地域/产品范围过滤后无可用知识库, 拒绝混合不同地市规则, 转人工]", query);
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

        // 4. BM25 通道: 逐变体召回, 去重取最高分
        List<Map.Entry<Long, Double>> bm25Hits = new ArrayList<>();
        for (String variant : variants) {
            bm25Hits.addAll(bm25Searcher.search(variant, tenantId, kbIds, RECALL_TOP_K));
        }
        bm25Hits = dedupMax(bm25Hits);
        Set<Long> bm25HitIds = bm25Hits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        // 5. 向量通道: 变体整体 embedding → Milvus 召回(embedding 失败跳过该通道)
        List<Map.Entry<Long, Double>> vectorHits = vectorSearch(variants, tenantId, kbIds);
        vectorHits = dedupMax(vectorHits);
        Set<Long> vectorHitIds = vectorHits.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        // 6. RRF 融合 Top20
        List<Map.Entry<Long, Double>> fused = rrfMerger.merge(List.of(bm25Hits, vectorHits), RECALL_TOP_K);
        Map<Long, Double> rrfMap = fused.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // 7. 已发布过滤(Milvus 无状态标量, 融合后统一判定)
        Set<Long> published = resultFilter.filterPublished(fused.stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        List<Map.Entry<Long, Double>> candidates = fused.stream()
                .filter(e -> published.contains(e.getKey()))
                .collect(Collectors.toList());

        // 8. 内容补全(顺序与候选一致, 缺失给空串)
        List<Long> candidateIds = candidates.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        Map<Long, String> contentsMap = resultFilter.getChunkContents(candidateIds);
        List<String> contents = candidateIds.stream()
                .map(id -> contentsMap.getOrDefault(id, "")).collect(Collectors.toList());

        // 9. 文档信息补全(chunkId -> documentId/documentName/versionNo)
        Map<Long, ChunkDocInfoDTO> docInfoMap = resultFilter.getChunkDocInfo(candidateIds);

        // 9.5 父子扩展(B3): 命中子块批量取父块上下文(去重 + token 预算, 供引用时回带完整章节)
        Map<Long, Long> parentMap = resultFilter.getChunkParents(candidateIds);
        java.util.Set<Long> parentIds = parentMap.values().stream()
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> parentContents = parentIds.isEmpty() ? Map.of()
                : resultFilter.getChunkContents(parentIds);
        int contextBudget = 1000; // 父块上下文总预算(字符)
        int contextUsed = 0;
        java.util.Map<Long, String> truncatedParents = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, String> e : parentContents.entrySet()) {
            if (contextUsed >= contextBudget) {
                break;
            }
            String content = e.getValue() == null ? "" : e.getValue();
            String truncated = content.length() > 300 ? content.substring(0, 300) : content;
            truncatedParents.put(e.getKey(), truncated);
            contextUsed += truncated.length();
        }

        // 10. 重排(候选为空或全部内容缺失时跳过模型调用, 避免无意义开销)
        List<Map.Entry<Integer, Float>> reranked;
        boolean allBlank = contents.isEmpty() || contents.stream().allMatch(StrUtil::isBlank);
        if (allBlank) {
            reranked = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) {
                reranked.add(Map.entry(i, 0F)); // 保持 RRF 顺序
            }
        } else {
            reranked = reranker.rerank(query, contents);
        }

        // 11. 组装响应
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
            if (results.size() >= topKFinal) {
                break;
            }
            int idx = r.getKey();
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            Long chunkId = candidates.get(idx).getKey();
            results.add(buildResult(chunkId, contentsMap, docInfoMap, rrfMap, r.getValue(),
                    bm25HitIds, vectorHitIds, parentMap, truncatedParents));
        }
        resp.setResults(results);
        recordTrace(query, resp, variants.size(), startMs); // F5 检索追踪
        // 12. 产品/品牌一致性门禁(结构化代码判定, 不依赖 LLM 提示词):
        //     问题明确涉及产品而证据文档均不覆盖该产品 -> 拒绝作答, 明示原因。
        //     降级原则(degrade-never-block): 检索有结果但文档信息 RPC 失败(docInfoMap 为空)
        //     时跳过门禁(无法判定=不误伤), 仅告警; 仅当确知结果集的产品归属且均不匹配时才阻断。
        List<String> questionProducts = analysis.getProducts() == null ? List.of() : analysis.getProducts();
        // 领域策略: 专利领域关闭产品/品牌一致性门禁(不破坏 GENERAL 客服场景)
        if (!domainPolicy.enableProductGate()) {
            questionProducts = List.of();
        }
        boolean docInfoUnavailable = !results.isEmpty() && (docInfoMap == null || docInfoMap.isEmpty());
        if (docInfoUnavailable) {
            log.warn("[search][query={} 文档信息获取失败, 跳过产品/品牌一致性门禁(降级)]", query);
        }
        Set<String> docProducts = docInfoUnavailable ? Set.of() : collectDocProducts(results, docInfoMap);
        boolean productMatch = docInfoUnavailable
                || questionProducts.isEmpty()
                || questionProducts.stream().anyMatch(p ->
                        docProducts.stream().anyMatch(dp -> dp.contains(p) || p.contains(dp)));
        if (!productMatch) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("问题涉及产品「" + String.join("、", questionProducts)
                    + "」, 现有资料仅覆盖「" + (docProducts.isEmpty() ? "无" : String.join("、", docProducts))
                    + "」, 无法确认其政策, 拒绝作答");
            resp.setAnswer(null);
        } else {
            // 13. 双回答者收敛(2026-08-21): 检索只负责召回/重排/门禁, 不再自己生成 answer。
            //     答案统一由证据管线(EvidenceService.evaluate → AnswerPipeline)产出——
            //     带充分性判定/冲突检测/Claim 逐句验证, 与对话工作台同一链路, 避免两套回答不一致。
            //     前端检索测试页已并行展示证据评估结果, 此处 answer 恒为 null。
            resp.setAnswer(null);
        }
        return resp;
    }

    /**
     * 领域路由(任务书 8.1): 单知识库/多知识库同领域 → 该领域策略; 跨领域 → 拒绝;
     * 无法获取/为空 → 回退 GENERAL。
     */
    private cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy resolveDomainPolicy(java.util.List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return domainPolicyRegistry.get("GENERAL");
        }
        try {
            java.util.Map<Long, String> domains = knowledgeApi.getKbDomainCodes(kbIds).getCheckedData();
            if (domains == null || domains.isEmpty()) {
                return domainPolicyRegistry.get("GENERAL");
            }
            java.util.Set<String> unique = new java.util.HashSet<>(domains.values());
            if (unique.size() == 1) {
                return domainPolicyRegistry.get(unique.iterator().next());
            }
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

    /**
     * D2 scope 硬过滤: 查询命中省市/产品 slot 时, 有 scope 配置的知识库必须匹配;
     * 无 scope 配置的知识库不受影响; scope RPC 失败降级不过滤(项目降级原则)并告警。
     */
    private java.util.List<Long> applyScopeFilter(QueryAnalysis analysis, java.util.List<Long> kbIds) {
        boolean hasProvince = StrUtil.isNotBlank(analysis.getProvince());
        boolean hasCity = StrUtil.isNotBlank(analysis.getCity());
        boolean hasProduct = analysis.getProducts() != null && !analysis.getProducts().isEmpty();
        if ((!hasProvince && !hasCity && !hasProduct) || kbIds == null || kbIds.isEmpty()) {
            return kbIds;
        }
        java.util.Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>> scopes;
        try {
            scopes = knowledgeApi.getKbScopes(kbIds).getCheckedData();
        } catch (Exception e) {
            log.warn("[applyScopeFilter][scope RPC 失败, 降级不过滤: {}]", e.getMessage());
            return kbIds;
        }
        if (scopes == null || scopes.isEmpty()) {
            return kbIds; // 无 scope 配置, 兼容现状
        }
        java.util.List<Long> filtered = new java.util.ArrayList<>();
        for (Long kbId : kbIds) {
            java.util.List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO> kbScopes = scopes.get(kbId);
            if (kbScopes == null || kbScopes.isEmpty()) {
                filtered.add(kbId);
                continue;
            }
            if (scopeMatches(analysis, kbScopes)) {
                filtered.add(kbId);
            }
        }
        return filtered;
    }

    /** scope 匹配: 城市精确优先于省份; 产品 scope 有配置才过滤 */
    private boolean scopeMatches(QueryAnalysis analysis,
                                 java.util.List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO> kbScopes) {
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
            java.util.List<String> productScopes = kbScopes.stream()
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

    /**
     * 解析知识库意图集(动态意图分类参考): kbIds 为空时回退用户可见知识库(需登录态)。
     *
     * @param kbIds 限定知识库编号列表(空 = 全部可见)
     * @return 意图集(按名称去重; 解析失败/无意图返回空, 调用方回退固定枚举)
     */
    List<IntentDTO> resolveIntents(List<Long> kbIds) {
        return resolveIntents(kbIds, SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 解析知识库意图集(动态意图分类参考): 逐知识库拉取启用中意图, 按意图名跨库去重合并。
     * <p>
     * kbIds 为空时回退用户可见知识库(需 userId, 供 RPC 无登录态路径显式传递);
     * 单库 RPC 失败仅跳过该库, 全部失败/无意图返回空列表 → 调用方回退固定枚举, 不阻断主链路。
     * <p>
     * 注意: 在 search() 流程中 kbIds 已在步骤 2 按可见集过滤且非空, 此处直接逐库拉取,
     * 不会触发可见集回退, 避免重复 RPC。
     *
     * @param kbIds  限定知识库编号列表(空 = 全部可见)
     * @param userId 用户编号(仅 kbIds 为空时用于回退可见集; RPC 路径显式传递)
     * @return 意图集(按名称去重合并, 保持首次出现顺序)
     */
    List<IntentDTO> resolveIntents(List<Long> kbIds, Long userId) {
        List<Long> ids = (kbIds != null && !kbIds.isEmpty()) ? kbIds
                : (userId == null ? List.of() : new ArrayList<>(resultFilter.getVisibleKbIds(userId)));
        if (ids.isEmpty()) {
            return List.of();
        }
        // 去重: 同一知识库重复传入不重复 RPC
        List<Long> distinctIds = ids.stream().distinct().collect(Collectors.toList());
        Map<String, IntentDTO> byName = new LinkedHashMap<>();
        for (Long kbId : distinctIds) {
            try {
                List<IntentDTO> intents = knowledgeApi.getKbIntents(kbId).getCheckedData();
                if (intents == null) {
                    continue;
                }
                for (IntentDTO intent : intents) {
                    // 按名称去重合并(跨库同名意图视为同一意图; 名称/说明已足够分类, kbId 可不参与)
                    if (intent != null && StrUtil.isNotBlank(intent.getName())) {
                        byName.putIfAbsent(intent.getName(), intent);
                    }
                }
            } catch (Exception e) {
                log.warn("[resolveIntents][知识库 {} 意图获取失败, 跳过该库: {}]", kbId, e.getMessage());
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** 收集结果涉及的全部文档产品(逗号分隔字段展开) */
    private Set<String> collectDocProducts(List<RetrievalRespVO.ResultVO> results,
                                           Map<Long, ChunkDocInfoDTO> docInfoMap) {
        Set<String> products = new HashSet<>();
        for (RetrievalRespVO.ResultVO r : results) {
            ChunkDocInfoDTO info = docInfoMap.get(r.getChunkId());
            if (info != null && StrUtil.isNotBlank(info.getProducts())) {
                for (String p : StrUtil.split(info.getProducts(), ',')) {
                    if (StrUtil.isNotBlank(p)) {
                        products.add(p.trim());
                    }
                }
            }
        }
        return products;
    }

    /** 向量通道召回: 变体整体 embedding, 失败跳过该通道(不阻断主链路) */
    private List<Map.Entry<Long, Double>> vectorSearch(List<String> variants, Long tenantId, List<Long> kbIds) {
        try {
            List<List<Float>> vectors = modelApi.embedding(variants).getCheckedData();
            if (vectors == null || vectors.isEmpty()) {
                return List.of();
            }
            return vectorSearcher.search(vectors, tenantId, kbIds, RECALL_TOP_K);
        } catch (Exception e) {
            log.warn("[vectorSearch][向量检索失败, 跳过向量通道: {}]", e.getMessage());
            return List.of();
        }
    }

    /** 去重取最高分(保留首次出现顺序, RRF 只依赖排名) */
    private List<Map.Entry<Long, Double>> dedupMax(List<Map.Entry<Long, Double>> list) {
        Map<Long, Double> map = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : list) {
            map.merge(e.getKey(), e.getValue(), Math::max);
        }
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
        vo.setRoute(resolveRoute(analysis)); // D3 路由标记
        return vo;
    }

    /** D3 路由标记(轻量 QueryPlanner): 超范围→ABSTAIN; scope 过滤过→SCOPE_FILTER_HYBRID_RAG; 默认混合检索 */
    private String resolveRoute(QueryAnalysis analysis) {
        if ("OUT_OF_SCOPE".equals(analysis.getIntent())) {
            return "ABSTAIN";
        }
        if (StrUtil.isNotBlank(analysis.getProvince()) || StrUtil.isNotBlank(analysis.getCity())) {
            return "SCOPE_FILTER_HYBRID_RAG";
        }
        return "HYBRID_RAG";
    }

    /** F5 检索追踪落库(审计/评测; 失败不阻断) */
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
            Map<Long, Long> parentMap, Map<Long, String> parentContents) {
        RetrievalRespVO.ResultVO vo = new RetrievalRespVO.ResultVO();
        vo.setChunkId(chunkId);
        vo.setContent(contentsMap.getOrDefault(chunkId, ""));
        ChunkDocInfoDTO docInfo = docInfoMap.get(chunkId);
        if (docInfo != null) {
            vo.setDocumentId(docInfo.getDocumentId());
            vo.setDocumentName(docInfo.getDocumentName());
            vo.setVersionNo(docInfo.getVersionNo());
        }
        vo.setRrfScore(rrfMap.get(chunkId));
        vo.setRerankScore(rerankScore);
        // B3 父子扩展: 命中子块回带父块上下文(引用仍锚定命中子块, 父块仅上下文)
        Long parentId = parentMap.get(chunkId);
        if (parentId != null) {
            vo.setContextChunkId(parentId);
            vo.setContextContent(parentContents.get(parentId));
        }
        // 命中通道按各通道去重后的命中集合精确标记
        List<String> channels = new ArrayList<>();
        if (bm25HitIds.contains(chunkId)) {
            channels.add("bm25");
        }
        if (vectorHitIds.contains(chunkId)) {
            channels.add("vector");
        }
        vo.setChannels(channels);
        return vo;
    }

}
