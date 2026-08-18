package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalReqVO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        // 1. 参数归一: topK 默认 5, 上限 20; 租户
        int topK = req.getTopK() == null || req.getTopK() <= 0 ? 5 : Math.min(req.getTopK(), RECALL_TOP_K);
        Long tenantId = SecurityFrameworkUtils.getLoginUser().getTenantId();

        // 2. 权限前置: 可见知识库计算 + 空集短路(在 LLM 调用之前, 避免无效消耗且防越权泄露)
        Set<Long> visibleKbIds = resultFilter.getVisibleKbIds();
        List<Long> kbIds = req.getKbIds() != null && !req.getKbIds().isEmpty()
                ? req.getKbIds().stream().filter(visibleKbIds::contains).distinct().collect(Collectors.toList())
                : new ArrayList<>(visibleKbIds);
        // ⚠️ 权限边界: 交集为空(请求只含不可见知识库 / 可见集获取失败)必须短路返回空,
        //    否则双检索器把空 kbIds 当"不限", 泄露不可见知识库内容(越权 0 容忍)
        if (kbIds.isEmpty()) {
            log.warn("[search][query={} 无可见知识库, 返回空]", req.getQuery());
            RetrievalRespVO empty = new RetrievalRespVO();
            empty.setQuery(req.getQuery());
            empty.setAnalysis(new RetrievalRespVO.AnalysisVO());
            empty.setChannels(new RetrievalRespVO.ChannelStatVO());
            empty.setResults(List.of());
            return empty;
        }

        // 3. 语义理解/改写/拆解: 变体 = 原句 + (改写 + 子问题), 去重限 6
        QueryAnalysis analysis = queryAnalysisService.analyze(req.getQuery());
        List<String> variants = new ArrayList<>();
        variants.add(req.getQuery());
        if (analysis.isSuccess()) {
            if (analysis.getRewrites() != null) {
                variants.addAll(analysis.getRewrites());
            }
            if (analysis.getSubQuestions() != null) {
                variants.addAll(analysis.getSubQuestions());
            }
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
            reranked = reranker.rerank(req.getQuery(), contents);
        }

        // 11. 组装响应
        RetrievalRespVO resp = new RetrievalRespVO();
        resp.setQuery(req.getQuery());
        resp.setAnalysis(buildAnalysis(analysis));
        RetrievalRespVO.ChannelStatVO stat = new RetrievalRespVO.ChannelStatVO();
        stat.setBm25(bm25Hits.size());
        stat.setVector(vectorHits.size());
        stat.setFused(fused.size());
        resp.setChannels(stat);

        List<RetrievalRespVO.ResultVO> results = new ArrayList<>();
        for (Map.Entry<Integer, Float> r : reranked) {
            if (results.size() >= topK) {
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
        return resp;
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
