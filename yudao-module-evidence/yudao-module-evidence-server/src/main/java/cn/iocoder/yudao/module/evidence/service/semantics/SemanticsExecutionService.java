package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.hutool.core.util.StrUtil;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义执行服务：单实体/逐实体/跨实体比较都必须先 hard-scope 到目标文档，禁止全局 TopK 垄断。
 * 业务实体身份由 DomainEntityIdentityProvider 提供，Core 不感知 applicationNo/planCode/deviceCode 等领域字段。
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
    private final List<DomainEntityIdentityProvider> identityProviders;

    @Autowired
    public SemanticsExecutionService(EvidenceAssembler assembler, AnswerPipeline answerPipeline,
                                     EvidenceProperties properties, KnowledgeApi knowledgeApi,
                                     List<DomainEntityIdentityProvider> identityProviders) {
        this.assembler = assembler;
        this.answerPipeline = answerPipeline;
        this.properties = properties;
        this.knowledgeApi = knowledgeApi;
        this.identityProviders = identityProviders == null ? List.of() : List.copyOf(identityProviders);
    }

    /** 源码兼容构造器：无 Domain Provider 时按 documentId 作为业务身份。 */
    public SemanticsExecutionService(EvidenceAssembler assembler, AnswerPipeline answerPipeline,
                                     EvidenceProperties properties, KnowledgeApi knowledgeApi) {
        this(assembler, answerPipeline, properties, knowledgeApi, List.of());
    }

    public record Result(List<Evidence> evidences, GenerationResult generation,
                         List<Long> entityIds, boolean overLimit, int limit) {
    }

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

    /** 旧调用兼容；未给 domainCode 时按 documentId 去重。 */
    public CompareResult executeCompare(String query, Long kbId, List<Long> entityIds,
                                        Long tenantId, Long userId, List<ChatTurnDTO> history,
                                        String traceId, boolean requireAllCoverage) {
        return executeCompare(query, kbId, null, entityIds, tenantId, userId, history, traceId, requireAllCoverage);
    }

    /**
     * CROSS_ENTITY_COMPARE：每个候选文档独立召回，再由 Domain Pack 身份策略去重。
     * requireAllCoverage=true 时，任一逻辑实体无证据都禁止进入生成阶段。
     */
    public CompareResult executeCompare(String query, Long kbId, String domainCode, List<Long> entityIds,
                                        Long tenantId, Long userId, List<ChatTurnDTO> history,
                                        String traceId, boolean requireAllCoverage) {
        List<Long> rawIds = entityIds == null || entityIds.isEmpty()
                ? collectPublishedDocumentIds(kbId)
                : entityIds.stream().distinct().toList();
        int limit = properties.getSemantics().getMaxSemanticEntities();
        if (kbId == null || rawIds.isEmpty()) {
            return new CompareResult(List.of(), null, rawIds, List.of(), false, limit, true);
        }
        if (rawIds.size() > limit) {
            return new CompareResult(null, null, rawIds, List.of(), true, limit, false);
        }

        List<Evidence> all = new ArrayList<>();
        List<Long> logicalIds = new ArrayList<>();
        List<Long> covered = new ArrayList<>();
        Set<String> seenIdentity = new LinkedHashSet<>();

        for (Long entityId : rawIds) {
            AssembledEvidence assembled = assembler.assemble(query, List.of(kbId), COMPARE_PER_ENTITY_TOP_K,
                    tenantId, userId, history, traceId, List.of(entityId));
            List<Evidence> one = assembled != null && assembled.getEvidences() != null
                    ? assembled.getEvidences() : List.of();

            String identity = resolveIdentity(domainCode, one.isEmpty() ? null : one.get(0), entityId);
            if (!seenIdentity.add(identity)) {
                log.info("[executeCompare][跳过重复业务实体: domain={}, identity={}, documentId={}]",
                        domainCode, identity, entityId);
                continue;
            }
            logicalIds.add(entityId);
            if (!one.isEmpty()) {
                covered.add(entityId);
                all.addAll(one);
            }
        }

        long coveredCount = covered.stream().distinct().count();
        boolean insufficient = logicalIds.size() < 2 || coveredCount < 2
                || (requireAllCoverage && coveredCount < logicalIds.size());
        if (insufficient || all.isEmpty()) {
            log.info("[executeCompare][跨实体证据覆盖不足: expected={}, covered={}, query={}]",
                    logicalIds.size(), coveredCount, query);
            return new CompareResult(all, null, logicalIds, covered, false, limit, true);
        }
        GenerationResult generation = answerPipeline.generateWithClaims(query, all, history);
        return new CompareResult(all, generation, logicalIds, covered, false, limit, false);
    }

    private String resolveIdentity(String domainCode, Evidence evidence, Long fallbackDocumentId) {
        if (StrUtil.isNotBlank(domainCode)) {
            for (DomainEntityIdentityProvider provider : identityProviders) {
                if (provider != null && domainCode.equalsIgnoreCase(provider.domainCode())) {
                    String key = provider.identityKey(evidence, fallbackDocumentId);
                    if (StrUtil.isNotBlank(key)) return key;
                }
            }
        }
        return "DOC:" + fallbackDocumentId;
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
