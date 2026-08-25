package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import cn.iocoder.yudao.module.evidence.service.generate.PatentExactClaimAnswerer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PATENT 权利要求精确能力。
 * 只在 trusted patent entity scope 内执行；检索只负责取候选，最终必须通过 CLAIMS + claimNo 元数据唯一确认。
 */
@Component
public class PatentClaimLookupCapability implements KnowledgeCapability {
    public static final String NAME = "patent_claim_lookup";

    private final PlannedEvidenceRetriever retriever;

    public PatentClaimLookupCapability(PlannedEvidenceRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "1",
                "读取一个已确认专利对象的指定权利要求原文或依赖关系。必须先通过结构化查询/多轮上下文得到唯一 trusted patent entity。",
                Map.of(
                        "claimNo", "必填。权利要求编号，正整数。",
                        "mode", "必填。RAW=原文；DEPENDENCY=引用/从属依赖关系。"
                ),
                Set.of("claimNo", "mode"), "PATENT_CLAIM_EXACT", true,
                Set.of(), Set.of("PATENT"), Set.of(), 8_000L, 1);
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || context.userId() == null) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "knowledge scope is incomplete");
        }
        if (!"PATENT".equalsIgnoreCase(context.domainCode())) {
            return CapabilityResult.failure(AgentStopReason.CAPABILITY_UNAVAILABLE, "patent capability requires PATENT domain");
        }
        if (context.contextEntityIds() == null || context.contextEntityIds().size() != 1) {
            return CapabilityResult.failure(AgentStopReason.NEED_USER_INPUT,
                    "exact patent claim lookup requires exactly one trusted patent entity");
        }

        int claimNo = positiveInt(arguments.get("claimNo"));
        if (claimNo <= 0) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, "claimNo must be a positive integer");
        }
        Mode mode = mode(arguments.get("mode"));
        if (mode == null) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, "mode must be RAW or DEPENDENCY");
        }

        String exactQuestion = mode == Mode.RAW
                ? "权利要求" + claimNo + "原文是什么"
                : "权利要求" + claimNo + "引用了哪些在先权利要求";
        PlannedEvidenceRetriever.Result result = retriever.search(exactQuestion, List.of(), List.of(context.kbId()),
                context.contextEntityIds(), 20, context.tenantId(), context.userId(), context.traceId());
        List<Evidence> exact = exactClaim(result.evidences(), claimNo);
        if (exact.size() != 1) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "claim metadata did not resolve to one unique published claim: claimNo=" + claimNo
                            + ", uniqueMatches=" + exact.size());
        }

        PatentExactClaimAnswerer.DirectAnswer direct = PatentExactClaimAnswerer.tryAnswer(exactQuestion, exact);
        if (direct == null || StrUtil.isBlank(direct.answer())) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "exact claim exists but deterministic claim answer could not be produced");
        }
        Output output = new Output(claimNo, mode.name(), exact, direct.answer());
        return CapabilityResult.success(output, Map.of(
                "outputCount", 1,
                "evidenceCount", 1,
                "claimNo", claimNo,
                "mode", mode.name(),
                "verifiedDocumentId", context.contextEntityIds().get(0)
        ));
    }

    private List<Evidence> exactClaim(List<Evidence> candidates, int claimNo) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence evidence : candidates) {
            if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) continue;
            try {
                var meta = JSONUtil.parseObj(evidence.getChunkMetadata());
                if (!"PATENT".equalsIgnoreCase(meta.getStr("domainCode"))) continue;
                if (!"CLAIMS".equalsIgnoreCase(meta.getStr("sectionType"))) continue;
                Integer actual = meta.getInt("claimNo");
                if (actual == null || actual != claimNo) continue;
                String normalizedContent = StrUtil.nullToEmpty(evidence.getContent()).replaceAll("\\s+", "").trim();
                if (normalizedContent.isEmpty()) continue;
                unique.putIfAbsent(normalizedContent, evidence);
            } catch (Exception ignore) { }
        }
        return List.copyOf(unique.values());
    }

    private int positiveInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try { return raw == null ? -1 : Integer.parseInt(String.valueOf(raw).trim()); }
        catch (Exception e) { return -1; }
    }

    private Mode mode(Object raw) {
        if (raw == null) return null;
        try { return Mode.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    private enum Mode { RAW, DEPENDENCY }

    public record Output(int claimNo, String mode, List<Evidence> evidences, String answer)
            implements AgentCapabilityOutput {
        @Override
        public String summary() {
            Evidence evidence = evidences == null || evidences.isEmpty() ? null : evidences.get(0);
            return "claimNo=" + claimNo + "; mode=" + mode + "; documentId="
                    + (evidence == null ? null : evidence.getDocumentId()) + "; answer=" + answer;
        }

        @Override
        public String progressHash() {
            Evidence evidence = evidences == null || evidences.isEmpty() ? null : evidences.get(0);
            return "claim:" + claimNo + ":" + mode + ":" + (evidence == null ? "none" : evidence.getChunkId());
        }

        @Override
        public String deterministicAnswer() {
            return answer;
        }
    }
}
