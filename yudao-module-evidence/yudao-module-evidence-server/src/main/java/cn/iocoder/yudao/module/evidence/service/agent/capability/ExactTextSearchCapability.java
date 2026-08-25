package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户明确要求原文逐字出现/包含时使用，不把 exact-text 退化为语义近似。 */
@Component
public class ExactTextSearchCapability implements KnowledgeCapability {
    public static final String NAME = "exact_text_search";
    private final PlannedEvidenceRetriever retriever;
    private final DomainEvidenceEntityMapperRegistry entityMapperRegistry;

    @Autowired
    public ExactTextSearchCapability(PlannedEvidenceRetriever retriever,
                                     DomainEvidenceEntityMapperRegistry entityMapperRegistry) {
        this.retriever = retriever;
        this.entityMapperRegistry = entityMapperRegistry;
    }

    /** 单测/迁移期兼容构造：未注册 Domain mapper 时只产出 Evidence，不猜 entityId。 */
    public ExactTextSearchCapability(PlannedEvidenceRetriever retriever) {
        this(retriever, new DomainEvidenceEntityMapperRegistry(List.of()));
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "3", "在当前授权知识库中执行逐字原文检索。仅用于用户明确要求‘原文包含/逐字出现/精确短语’的场景；若当前 Domain 显式注册 Evidence->Entity 映射，会额外产出 candidateEntityIds，但原文命中本身不会升级为 trusted entity。",
                Map.of("text", "必填。需要逐字匹配的原文短语。", "topK", "可选。1~50，默认 20。",
                        "scope", "可选。CURRENT_KB 或 CONTEXT；CONTEXT 只检索上一轮已验证对象集合。"),
                Set.of("text"), "EXACT_TEXT_EVIDENCE_WITH_CANDIDATES", true, Set.of(), Set.of(), Set.of(), 8_000L, 50);
    }

    @Override
    public String canonicalExecutionKey(CapabilityInvocationContext context, Map<String, Object> arguments) {
        String text = normalizeText(arguments == null ? null : arguments.get("text"));
        String scope = arguments == null ? "CURRENT_KB" : String.valueOf(arguments.getOrDefault("scope", "CURRENT_KB")).trim().toUpperCase();
        int topK = intValue(arguments == null ? null : arguments.get("topK"), 20, 1, 50);
        return "text=" + text + ";scope=" + scope + ";topK=" + topK;
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || context.userId() == null)
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "knowledge scope is incomplete");
        String text = String.valueOf(arguments.getOrDefault("text", "")).trim();
        if (StrUtil.isBlank(text)) return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, "text must not be blank");
        List<Long> documentIds = scope(arguments.get("scope"), context);
        if (documentIds == null) return CapabilityResult.failure(AgentStopReason.NEED_USER_INPUT,
                "conversation scope was requested but no verified context entity set exists");
        int topK = intValue(arguments.get("topK"), 20, 1, 50);
        PlannedEvidenceRetriever.Result result = retriever.exactText(text, List.of(context.kbId()),
                documentIds.isEmpty() ? null : documentIds, topK, context.tenantId(), context.userId(), context.traceId());
        if (result.failed()) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    StrUtil.blankToDefault(result.errorMessage(), "exact-text retrieval source failed"));
        }
        List<Evidence> evidences = result.evidences();
        List<Long> candidateEntityIds = entityMapperRegistry.candidateEntityIds(context.domainCode(), evidences);
        boolean exactTotal = Boolean.TRUE.equals(result.totalHitsExact());
        boolean authoritativeEmpty = exactTotal && result.totalHits() != null && result.totalHits() == 0L;
        Output output = new Output(evidences, candidateEntityIds, result.totalHits(), result.totalHitsExact(), summary(evidences));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evidenceCount", evidences.size());
        metadata.put("candidateEntityCount", candidateEntityIds.size());
        metadata.put("candidateEntityMapped", entityMapperRegistry.hasMapper(context.domainCode()));
        metadata.put("entityTrust", "CANDIDATE");
        metadata.put("totalHits", result.totalHits() == null ? -1L : result.totalHits());
        metadata.put("totalHitsExact", exactTotal);
        metadata.put("candidateTotalHits", result.candidateTotalHits() == null ? -1L : result.candidateTotalHits());
        metadata.put("retrievalOutcome", result.status().name());
        metadata.put("completeDataset", exactTotal);
        metadata.put("authoritativeEmpty", authoritativeEmpty);
        metadata.put("outputComplete", true);
        return CapabilityResult.success(output, metadata);
    }

    private List<Long> scope(Object raw, CapabilityInvocationContext context) {
        String scope = raw == null ? "CURRENT_KB" : String.valueOf(raw).trim().toUpperCase();
        if (!"CONTEXT".equals(scope)) return List.of();
        return context.contextEntityIds().isEmpty() ? null : context.contextEntityIds();
    }

    private int intValue(Object raw, int def, int min, int max) {
        int value = def;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeText(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim().replaceAll("\\s+", " ");
    }

    private String summary(List<Evidence> evidences) {
        return evidences.stream().limit(8).map(e -> "doc=" + e.getDocumentId() + ",name="
                + StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 80) + ",text="
                + StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 180)).collect(Collectors.joining(" | "));
    }

    public record Output(List<Evidence> evidences,
                         List<Long> candidateEntityIds,
                         Long totalHits,
                         Boolean totalHitsExact,
                         String summary) implements AgentCapabilityOutput {
        public Output {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            candidateEntityIds = candidateEntityIds == null ? List.of() : List.copyOf(candidateEntityIds);
        }

        @Override public String progressHash() {
            if (evidences.isEmpty()) return "EMPTY";
            return evidences.stream().map(e -> String.valueOf(e.getChunkId())).collect(Collectors.joining(","));
        }
    }
}
