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
 * 语义执行服务：单实体/逐实体/跨实体比较都必须先 hard-scope 到目标文档，禁止全局 TopK 垄断。
 */
@Slf4j
@Service
public class SemanticsExecutionService {

    private static final int PER_ENTITY_TOP_K = 4;
    private static final int COMPARE_PER_ENTITY_TOP_K = 2;

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

    public record Result(List<Evidence> evidences, GenerationResult generation,
                         List<Long> entityIds, boolean overLimit, int limit) {
    }

    /** 跨实体比较结果：coveredEntityIds 用于 Coverage Guard，compare 不允许“4个对象只召回1个”。 */
    public record CompareResult(List<Evidence> evidences, GenerationResult generation,
                                List<Long> entityIds, List<Long> coveredEntityIds,
                                boolean overLimit, int limit, boolean coverageInsufficient) {
    }

    public Result execute(String query, Long kbId, List<Long> entityIds, Long tenantId, Long userId,
                          List<ChatTurnDTO> history, String traceId) {
        if (kbId == null || entityIds == null || entityIds.isEmpty()) {
            return new Result(List.of(), null, entityIds == null ? List.of() : entityIds, false, 0);
        }
        int limit = properties.getSemantics().getMaxSemanticEntities();
        List<Long> ids = entityIds.stream().distinct().toList();
        if (ids.size() > limit) {
            log.info("[execute][entityCount({}) > maxSemanticEntities({}), 要求缩小范围: query={}]", ids.size(), limit, query);
            return new Result(null, null, ids, true, limit);
        }
        List<Evidence> all = new ArrayList<>();
        for (Long entityId : ids) {
            AssembledEvidence assembled = assembler.assemble(query, List.of(kbId), PER_ENTITY_TOP_K,
                    tenantId, userId, history, traceId, List.of(entityId));
            if (assembled != null && assembled.getEvidences() != null) all.addAll(assembled.getEvidences());
        }
        if (all.isEmpty()) return new Result(List.of(), null, ids, false, limit);
        GenerationResult generation = answerPipeline.generateWithClaims(query, all, history);
        return new Result(all, generation, ids, false, limit);
    }

    public Result executeCrossEntity(String query, Long kbId, Long tenantId, Long userId,
                                     List<ChatTurnDTO> history, String traceId) {
        if (kbId == null) return new Result(List.of(), null, List.of(), false, 0);
        return execute(query, kbId, collectPublishedDocumentIds(kbId), tenantId, userId, history, traceId);
    }

    /**
     * CROSS_ENTITY_COMPARE：对每个候选实体独立检索，再一次综合。
     * requireAllCoverage=true 时，任一实体无证据都禁止进入生成阶段。
     */
    public CompareResult executeCompare(String query, Long kbId, List<Long> entityIds,
                                        Long tenantId, Long userId, List<ChatTurnDTO> history,
                                        String traceId, boolean requireAllCoverage) {
        List<Long> ids = entityIds == null || entityIds.isEmpty()
                ? collectPublishedDocumentIds(kbId)
                : entityIds.stream().distinct().toList();
        int limit = properties.getSemantics().getMaxSemanticEntities();
        if (kbId == null || ids.isEmpty()) {
            return new CompareResult(List.of(), null, ids, List.of(), false, limit, true);
        }
        if (ids.size() > limit) {
            return new CompareResult(null, null, ids, List.of(), true, limit, false);
        }
        List<Evidence> all = new ArrayList<>();
        List<Long> covered = new ArrayList<>();
        for (Long entityId : ids) {
            AssembledEvidence assembled = assembler.assemble(query, List.of(kbId), COMPARE_PER_ENTITY_TOP_K,
                    tenantId, userId, history, traceId, List.of(entityId));
            List<Evidence> one = assembled != null && assembled.getEvidences() != null
                    ? assembled.getEvidences() : List.of();
            if (!one.isEmpty()) {
                covered.add(entityId);
                all.addAll(one);
            }
        }
        boolean insufficient = covered.stream().distinct().count() < 2
                || (requireAllCoverage && covered.stream().distinct().count() < ids.size());
        if (insufficient || all.isEmpty()) {
            log.info("[executeCompare][跨实体证据覆盖不足: expected={}, covered={}, query={}]", ids.size(), covered.size(), query);
            return new CompareResult(all, null, ids, covered, false, limit, true);
        }
        GenerationResult generation = answerPipeline.generateWithClaims(query, all, history);
        return new CompareResult(all, generation, ids, covered, false, limit, false);
    }

    private List<Long> collectPublishedDocumentIds(Long kbId) {
        if (kbId == null) return List.of();
        try {
            CommonResult<List<Long>> resp = knowledgeApi.getPublishedDocumentIds(kbId);
            return resp != null && resp.isSuccess() && resp.getData() != null
                    ? resp.getData().stream().distinct().toList() : List.of();
        } catch (Exception e) {
            log.warn("[collectPublishedDocumentIds][kb({}) 枚举已发布文档失败: {}]", kbId, e.getMessage());
            return List.of();
        }
    }
}
