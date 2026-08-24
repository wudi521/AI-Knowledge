package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * PER_ENTITY_SEMANTIC 语义执行(CQ-38): 对明确实体集逐实体 SCOPED_RAG。
 * <p>
 * 场景: 多轮中引用了上一轮结果集, 但属性("核心技术分别是什么"/"技术方案")无法结构化消解
 * (无注册 metric/field) → 对每个实体限定文档检索(hard scope), 聚合证据后一次生成"每实体一值"回答。
 * <p>
 * CROSS_ENTITY_SEMANTIC: 无历史实体集时, 从知识库枚举已发布文档作为候选实体集, 再做逐实体语义执行。
 * <p>
 * 约束:
 * - 实体数受 {@code yudao.evidence.semantics.max-semantic-entities} 限制, 超限 → CLARIFY(禁止静默截断);
 * - 逐实体检索必须限定目标文档(SCOPED_RAG), 禁止全库检索后过滤;
 * - 无任何证据 → answerable=false(不猜); 生成失败/claimFail → 透传。
 */
@Slf4j
@Service
public class SemanticsExecutionService {

    /** 逐实体 topK(每实体少量证据即可支撑逐项回答, 避免吞没其他实体) */
    private static final int PER_ENTITY_TOP_K = 4;

    private final EvidenceAssembler assembler;
    private final AnswerPipeline answerPipeline;
    private final EvidenceProperties properties;
    private final KnowledgeApi knowledgeApi;

    public SemanticsExecutionService(EvidenceAssembler assembler, AnswerPipeline answerPipeline,
                                     EvidenceProperties properties, KnowledgeApi knowledgeApi) {
        this.assembler = assembler;
        this.answerPipeline = answerPipeline;
        this.properties = properties;
        this.knowledgeApi = knowledgeApi;
    }

    /** 语义执行结果 */
    public record Result(List<Evidence> evidences, GenerationResult generation,
                         List<Long> entityIds, boolean overLimit, int limit) {
    }

    /**
     * 对每个实体执行限定文档检索, 聚合证据后生成逐实体回答。
     *
     * @param entityIds 明确实体集(已消解, 非空)
     * @return overLimit=true 表示超限需 CLARIFY; evidences 为空表示无证据; 否则含生成结果
     */
    public Result execute(String query, Long kbId, List<Long> entityIds, Long tenantId, Long userId,
                          List<ChatTurnDTO> history, String traceId) {
        if (kbId == null || entityIds == null || entityIds.isEmpty()) {
            return new Result(List.of(), null, entityIds == null ? List.of() : entityIds, false, 0);
        }
        int limit = properties.getSemantics().getMaxSemanticEntities();
        List<Long> ids = entityIds.stream().distinct().toList();
        if (ids.size() > limit) {
            // 超限: 不静默截断, 由调用方要求缩小范围
            log.info("[execute][entityCount({}) > maxSemanticEntities({}), 要求缩小范围: query={}]",
                    ids.size(), limit, query);
            return new Result(null, null, ids, true, limit);
        }
        List<Evidence> all = new ArrayList<>();
        for (Long entityId : ids) {
            // 逐实体 SCOPED_RAG: 检索侧 hard scope 到单文档
            AssembledEvidence assembled = assembler.assemble(query, List.of(kbId), PER_ENTITY_TOP_K,
                    tenantId, userId, history, traceId, List.of(entityId));
            if (assembled != null && assembled.getEvidences() != null) {
                all.addAll(assembled.getEvidences());
            }
        }
        if (all.isEmpty()) {
            return new Result(List.of(), null, ids, false, limit);
        }
        GenerationResult generation = answerPipeline.generateWithClaims(query, all, history);
        return new Result(all, generation, ids, false, limit);
    }

    /**
     * CROSS_ENTITY_SEMANTIC(CQ-38): 无历史实体集时, 从知识库枚举已发布文档作为候选实体集,
     * 再做逐实体语义执行。实体数受 maxSemanticEntities 限制, 超限 → overLimit(要求缩小范围)。
     */
    public Result executeCrossEntity(String query, Long kbId, Long tenantId, Long userId,
                                     List<ChatTurnDTO> history, String traceId) {
        if (kbId == null) {
            return new Result(List.of(), null, List.of(), false, 0);
        }
        List<Long> ids = collectPublishedDocumentIds(kbId);
        return execute(query, kbId, ids, tenantId, userId, history, traceId);
    }

    /** 枚举知识库下已发布文档 id(领域无关; RPC 失败返回空集) */
    private List<Long> collectPublishedDocumentIds(Long kbId) {
        try {
            CommonResult<List<Long>> resp = knowledgeApi.getPublishedDocumentIds(kbId);
            return resp != null && resp.isSuccess() && resp.getData() != null
                    ? resp.getData() : List.of();
        } catch (Exception e) {
            log.warn("[collectPublishedDocumentIds][kb({}) 枚举已发布文档失败: {}]", kbId, e.getMessage());
            return List.of();
        }
    }

}
