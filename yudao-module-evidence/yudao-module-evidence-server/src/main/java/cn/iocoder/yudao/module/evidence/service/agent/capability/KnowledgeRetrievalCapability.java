package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把现有 BM25 + Vector + Fusion + Rerank 整条检索链包装成一个 Agent 能力。
 * Planner 不看也不能选择内部检索算法。
 */
@Component
public class KnowledgeRetrievalCapability implements KnowledgeCapability {
    public static final String NAME = "knowledge_retrieval";

    private final PlannedEvidenceRetriever retriever;

    public KnowledgeRetrievalCapability(PlannedEvidenceRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "1",
                "在当前已授权知识库中检索与查询语义相关的知识证据；内部自动完成关键词/向量/融合/重排。",
                Map.of(
                        "query", "必填。保持原始目标语义的检索表达，不得从候选中发明新的硬事实。",
                        "variants", "可选。最多 5 个保持原意的同义检索表达。",
                        "topK", "可选。1~20，默认 8。"
                ),
                Set.of("query"), "EVIDENCE_LIST", true,
                Set.of(), Set.of(), Set.of(), 8_000L, 20);
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || context.userId() == null) {
            return CapabilityResult.failure(cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason.PERMISSION_DENIED,
                    "knowledge scope is incomplete");
        }
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (StrUtil.isBlank(query)) {
            return CapabilityResult.failure(cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason.INVALID_CAPABILITY_CALL,
                    "query must not be blank");
        }
        List<String> variants = strings(arguments.get("variants"), 5);
        int topK = clampTopK(arguments.get("topK"));
        PlannedEvidenceRetriever.Result result = retriever.search(query, variants, List.of(context.kbId()), null,
                topK, context.tenantId(), context.userId(), context.traceId());
        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        Output output = new Output(evidences, result.totalHits(), result.totalHitsExact(), summary(evidences));
        return CapabilityResult.success(output, Map.of(
                "evidenceCount", evidences.size(),
                "totalHits", result.totalHits() == null ? -1L : result.totalHits(),
                "totalHitsExact", Boolean.TRUE.equals(result.totalHitsExact())
        ));
    }

    private int clampTopK(Object raw) {
        int value = 8;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) {
            try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        }
        return Math.max(1, Math.min(20, value));
    }

    private List<String> strings(Object raw, int limit) {
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object value : iterable) {
            String text = value == null ? null : String.valueOf(value).trim();
            if (StrUtil.isNotBlank(text) && !out.contains(text)) out.add(text);
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    private String summary(List<Evidence> evidences) {
        return evidences.stream().limit(8)
                .map(e -> "doc=" + e.getDocumentId() + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 80)
                        + ",score=" + e.getScore() + ",text="
                        + StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 180))
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
