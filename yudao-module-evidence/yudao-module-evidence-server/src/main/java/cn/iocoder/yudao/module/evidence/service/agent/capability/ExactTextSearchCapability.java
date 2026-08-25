package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户明确要求原文逐字出现/包含时使用，不把 exact-text 退化为语义近似。 */
@Component
public class ExactTextSearchCapability implements KnowledgeCapability {
    public static final String NAME = "exact_text_search";
    private final PlannedEvidenceRetriever retriever;

    public ExactTextSearchCapability(PlannedEvidenceRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "1",
                "在当前授权知识库中执行逐字原文检索。仅用于用户明确要求‘原文包含/逐字出现/精确短语’的场景。",
                Map.of(
                        "text", "必填。需要逐字匹配的原文短语。",
                        "topK", "可选。1~50，默认 20。",
                        "scope", "可选。CURRENT_KB 或 CONTEXT；CONTEXT 只检索上一轮已验证对象集合。"
                ), Set.of("text"), "EXACT_TEXT_EVIDENCE", true,
                Set.of(), Set.of(), Set.of(), 8_000L, 50);
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || context.userId() == null) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "knowledge scope is incomplete");
        }
        String text = String.valueOf(arguments.getOrDefault("text", "")).trim();
        if (StrUtil.isBlank(text)) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, "text must not be blank");
        }
        List<Long> documentIds = scope(arguments.get("scope"), context);
        if (documentIds == null) {
            return CapabilityResult.failure(AgentStopReason.NEED_USER_INPUT,
                    "conversation scope was requested but no verified context entity set exists");
        }
        int topK = intValue(arguments.get("topK"), 20, 1, 50);
        PlannedEvidenceRetriever.Result result = retriever.exactText(text, List.of(context.kbId()), documentIds,
                topK, context.tenantId(), context.userId(), context.traceId());
        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        Output output = new Output(evidences, result.totalHits(), result.totalHitsExact(), summary(evidences));
        return CapabilityResult.success(output, Map.of(
                "evidenceCount", evidences.size(),
                "totalHits", result.totalHits() == null ? -1L : result.totalHits(),
                "totalHitsExact", Boolean.TRUE.equals(result.totalHitsExact())
        ));
    }

    private List<Long> scope(Object raw, CapabilityInvocationContext context) {
        String scope = raw == null ? "CURRENT_KB" : String.valueOf(raw).trim().toUpperCase();
        if (!"CONTEXT".equals(scope)) return nullSafeCurrentScope();
        return context.contextEntityIds().isEmpty() ? null : context.contextEntityIds();
    }

    /** null 表示不传 documentIds，即当前 KB 全范围。 */
    private List<Long> nullSafeCurrentScope() { return java.util.Collections.emptyList(); }

    private int intValue(Object raw, int def, int min, int max) {
        int value = def;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        return Math.max(min, Math.min(max, value));
    }

    private String summary(List<Evidence> evidences) {
        return evidences.stream().limit(8)
                .map(e -> "doc=" + e.getDocumentId() + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 80)
                        + ",text=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 180))
                .collect(Collectors.joining(" | "));
    }

    public record Output(List<Evidence> evidences, Long totalHits, Boolean totalHitsExact, String summary)
            implements AgentCapabilityOutput {
        @Override
        public String progressHash() {
            if (evidences == null || evidences.isEmpty()) return "EMPTY";
            return evidences.stream().map(e -> String.valueOf(e.getChunkId())).collect(Collectors.joining(","));
        }
    }
}
