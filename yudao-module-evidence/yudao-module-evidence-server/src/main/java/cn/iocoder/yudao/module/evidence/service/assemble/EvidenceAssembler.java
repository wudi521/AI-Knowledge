package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 证据组装器: 调用检索 RPC 召回证据片段, 归一化得分, 组装为领域 {@link Evidence} 列表
 * <p>
 * 降级原则: 检索 RPC 失败/异常时返回空证据集并记录告警日志, 不向调用方抛异常(由后续充分性判定兜底)。
 */
@Slf4j
@Component
public class EvidenceAssembler {

    /** topK 默认值(略大于检索默认 5, 给去重/筛选留余量) */
    private static final int DEFAULT_TOP_K = 8;

    @Resource
    private RetrievalApi retrievalApi;

    /**
     * 组装证据
     *
     * @param query    检索内容
     * @param kbIds    限定知识库编号列表(空 = 全部可见知识库)
     * @param topK     返回条数(空则默认 8)
     * @param tenantId 租户编号(RPC 无登录态, 显式传递)
     * @param userId   用户编号(权限过滤用)
     * @return 组装结果(证据按得分降序); 检索失败时返回空证据集, 不抛异常
     */
    public AssembledEvidence assemble(String query, List<Long> kbIds, Integer topK, Long tenantId, Long userId) {
        // 1. 调用检索 RPC(topK 为空时默认 8)
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(query);
        req.setKbIds(kbIds);
        req.setTopK(topK != null ? topK : DEFAULT_TOP_K);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        CommonResult<RetrievalSearchRespDTO> resp;
        try {
            resp = retrievalApi.search(req);
        } catch (Exception e) {
            // 网络/序列化等异常: 优雅降级
            log.warn("[assemble][query({}) 调用检索 RPC 异常, 降级返回空证据]", query, e);
            return AssembledEvidence.empty();
        }
        // 2. 业务失败/空响应: 优雅降级
        if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
            log.warn("[assemble][query({}) 检索 RPC 失败: code({}) msg({}), 降级返回空证据]", query,
                    resp != null ? resp.getCode() : null, resp != null ? resp.getMsg() : null);
            return AssembledEvidence.empty();
        }
        RetrievalSearchRespDTO data = resp.getData();
        List<RetrievalResultDTO> results = data.getResults() != null ? data.getResults() : Collections.emptyList();

        // 3. 原始分选取(优先重排分, 缺失回退 RRF 分) → 批次内 min-max 归一化到 0~1
        List<Double> rawScores = new ArrayList<>(results.size());
        for (RetrievalResultDTO result : results) {
            rawScores.add(pickRawScore(result));
        }
        List<Double> normalizedScores = EvidenceSimilarity.minMaxNormalize(rawScores);

        // 4. 映射为领域 Evidence
        List<Evidence> evidences = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            RetrievalResultDTO result = results.get(i);
            evidences.add(Evidence.builder()
                    .chunkId(result.getChunkId())
                    .content(result.getContent())
                    .documentId(result.getDocumentId() != null ? String.valueOf(result.getDocumentId()) : null)
                    .documentName(result.getDocumentName())
                    .versionNo(result.getVersionNo())
                    .score(normalizedScores.get(i))
                    // 检索 RPC 不暴露逐条证据的产品归属, 保持空列表(Task 4 实体覆盖率退化为 questionProducts 覆盖检查)
                    .products(Collections.emptyList())
                    .channels(result.getChannels() != null ? new ArrayList<>(result.getChannels()) : new ArrayList<>())
                    .build());
        }

        // 5. 按得分降序(去重器依赖 "首个 = 最高分" 的先序)
        evidences.sort(Comparator.comparingDouble(Evidence::getScore).reversed());

        // 6. 透传问题产品/一致性门禁信息(供冲突判定与充分性判定使用)
        return new AssembledEvidence(evidences,
                data.getQuestionProducts() != null ? new ArrayList<>(data.getQuestionProducts()) : new ArrayList<>(),
                data.getAnswerBlocked(), data.getAnswerReason());
    }

    /**
     * 选取原始分: 优先重排分(rerankScore 有值且非负), 否则回退 RRF 融合分(rrfScore)
     *
     * @return 原始分(两者均缺失时返回 null, 归一化按 0 处理)
     */
    private Double pickRawScore(RetrievalResultDTO result) {
        if (result.getRerankScore() != null && result.getRerankScore() >= 0) {
            return result.getRerankScore().doubleValue();
        }
        return result.getRrfScore();
    }

}
