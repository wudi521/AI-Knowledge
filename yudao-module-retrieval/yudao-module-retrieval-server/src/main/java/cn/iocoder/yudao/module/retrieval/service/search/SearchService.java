package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
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
 */
@Slf4j
@Service
public class SearchService {

    /** 召回/重排候选上限(控制 LLM 打分成本) */
    private static final int RECALL_TOP_K = 20;
    /** 检索变体上限 */
    private static final int VARIANT_LIMIT = 6;

    /** 总结回答系统提示词: 只依据证据作答, 标注引用, 证据不足明说 */
    private static final String ANSWER_SYSTEM_PROMPT = """
            你是企业客服助手。基于给定的"证据片段"回答用户问题:
            1. 只能依据证据内容作答, 不得编造事实;
            2. 回答中在关键结论句后标注证据编号 [C1][C2](编号对应证据列表顺序);
            3. 证据不足时, 直接说明"根据现有资料无法确定", 并列出已确认的信息;
            4. 语言简洁口语化, 先给结论再给依据;
            5. **产品/品牌一致性校验(强制)**: 若问题明确指出具体产品(如"苹果13"、"iPhone")或品牌, 而证据片段中没有任何该产品的条款(证据属于其他产品, 如 X100 Pro), 则必须回答"现有资料中未收录{该产品}的售后政策, 无法确认其政策", 并说明现有资料覆盖的产品范围, 严禁把其他产品的条款套用到该产品上; 仅当证据中确实包含该产品/品牌的条款时才可正常作答。
            """;

    @Resource
    private QueryAnalysisService queryAnalysisService;
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
        QueryAnalysis analysis = queryAnalysisService.analyze(query, history);
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
            results.add(buildResult(chunkId, contentsMap, docInfoMap, rrfMap, r.getValue(), bm25HitIds, vectorHitIds));
        }
        resp.setResults(results);
        // 12. 产品/品牌一致性门禁(结构化代码判定, 不依赖 LLM 提示词):
        //     问题明确涉及产品而证据文档均不覆盖该产品 -> 拒绝作答, 明示原因
        List<String> questionProducts = analysis.getProducts() == null ? List.of() : analysis.getProducts();
        Set<String> docProducts = collectDocProducts(results, docInfoMap);
        boolean productMatch = questionProducts.isEmpty()
                || questionProducts.stream().anyMatch(p ->
                        docProducts.stream().anyMatch(dp -> dp.contains(p) || p.contains(dp)));
        if (!productMatch) {
            resp.setAnswerBlocked(true);
            resp.setAnswerReason("问题涉及产品「" + String.join("、", questionProducts)
                    + "」, 现有资料仅覆盖「" + (docProducts.isEmpty() ? "无" : String.join("、", docProducts))
                    + "」, 无法确认其政策, 拒绝作答");
            resp.setAnswer(null);
        } else {
            // 13. 大模型总结回答(基于 TopN 证据 + 问题实体, 带 [C1][C2] 引用; 失败置 null 不阻断)
            resp.setAnswer(generateAnswer(query, analysis.getEntities(), results));
        }
        return resp;
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

    /** 基于检索结果生成总结回答(LLM; 引用编号 C1.. 对应 results 顺序; 失败返回 null) */
    private String generateAnswer(String query, List<String> entities, List<RetrievalRespVO.ResultVO> results) {
        if (results.isEmpty()) {
            return null;
        }
        try {
            StringBuilder evidence = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                evidence.append("[C").append(i + 1).append("] ").append(results.get(i).getContent()).append("\n\n");
            }
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(ANSWER_SYSTEM_PROMPT);
            String user = "问题: " + query;
            if (entities != null && !entities.isEmpty()) {
                user += "\n\n问题实体(用于品牌/产品一致性校验): " + String.join("、", entities);
            }
            user += "\n\n证据片段:\n" + evidence;
            req.setUser(user);
            String answer = modelApi.chat(req).getCheckedData();
            return StrUtil.isBlank(answer) ? null : answer;
        } catch (Exception e) {
            log.warn("[generateAnswer][生成回答失败: {}]", e.getMessage());
            return null;
        }
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
        return vo;
    }

    private RetrievalRespVO.ResultVO buildResult(Long chunkId, Map<Long, String> contentsMap,
            Map<Long, ChunkDocInfoDTO> docInfoMap, Map<Long, Double> rrfMap, Float rerankScore,
            Set<Long> bm25HitIds, Set<Long> vectorHitIds) {
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
