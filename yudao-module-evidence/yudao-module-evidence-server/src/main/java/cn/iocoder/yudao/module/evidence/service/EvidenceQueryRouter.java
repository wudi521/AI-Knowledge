package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evidence/Chat/Eval Runner 的统一顶层查询路由。
 * V1.1 迁移期支持 V3 / AGENT / AGENT_WITH_V3_FALLBACK，避免各入口各自维护一套切换逻辑。
 */
@Service
public class EvidenceQueryRouter {
    private static final Set<String> SAFE_FALLBACK_REASONS = Set.of(
            "CAPABILITY_UNAVAILABLE", "MAX_STEPS", "MAX_LLM_CALLS", "TIME_BUDGET_EXCEEDED",
            "REPEATED_CALL", "NO_PROGRESS", "INVALID_CAPABILITY_CALL", "AGENT_SINGLE_KB_REQUIRED");

    private final EvidenceQueryEngineV3Facade v3Facade;
    private final AgenticEvidenceFacade agentFacade;
    private final EvidenceProperties properties;

    public EvidenceQueryRouter(EvidenceQueryEngineV3Facade v3Facade,
                               AgenticEvidenceFacade agentFacade,
                               EvidenceProperties properties) {
        this.v3Facade = v3Facade;
        this.agentFacade = agentFacade;
        this.properties = properties;
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String traceId,
                                           String domainCode, String contextResolutionJson,
                                           QueryPlanBudgetDTO legacyBudget) {
        String mode = mode();
        if ("V3".equals(mode)) {
            return v3(query, kbIds, topK, tenantId, userId, history, skipSlotDetection,
                    traceId, domainCode, contextResolutionJson, legacyBudget);
        }

        EvidenceEvaluateRespVO agent = agentFacade.evaluateUnrecorded(query, kbIds, domainCode,
                tenantId, userId, history, contextResolutionJson, traceId);
        if ("AGENT".equals(mode)
                || Boolean.TRUE.equals(agent.getAnswerable())
                || "NEED_USER_INPUT".equals(agent.getReasonCode())
                || !shouldFallback(agent.getReasonCode())) {
            agentFacade.record(agent);
            return agent;
        }

        EvidenceEvaluateRespVO v3 = v3(query, kbIds, topK, tenantId, userId, history,
                skipSlotDetection, agent.getTraceId(), domainCode, contextResolutionJson, legacyBudget);
        mergeFallbackStages(agent, v3);
        return v3;
    }

    public String mode() {
        String configured = properties == null || properties.getAgent() == null
                ? "V3" : properties.getAgent().getMode();
        if (configured == null) return "V3";
        String normalized = configured.trim().toUpperCase(Locale.ROOT);
        return Set.of("V3", "AGENT", "AGENT_WITH_V3_FALLBACK").contains(normalized) ? normalized : "V3";
    }

    private EvidenceEvaluateRespVO v3(String query, List<Long> kbIds, Integer topK,
                                      Long tenantId, Long userId, List<ChatTurnDTO> history,
                                      Boolean skipSlotDetection, String traceId, String domainCode,
                                      String contextResolutionJson, QueryPlanBudgetDTO legacyBudget) {
        return v3Facade.evaluate(query, kbIds, topK, tenantId, userId,
                history == null ? List.of() : history, skipSlotDetection, traceId,
                domainCode, contextResolutionJson, legacyBudget);
    }

    private boolean shouldFallback(String reasonCode) {
        return reasonCode != null && SAFE_FALLBACK_REASONS.contains(reasonCode);
    }

    private void mergeFallbackStages(EvidenceEvaluateRespVO agent, EvidenceEvaluateRespVO v3) {
        List<QueryStageTimingDTO> merged = new ArrayList<>();
        if (agent.getStages() != null) merged.addAll(agent.getStages());
        QueryStageTimingDTO fallback = new QueryStageTimingDTO();
        fallback.setStage("AGENT_FALLBACK_TO_V3");
        fallback.setStatus("SUCCEEDED");
        fallback.setSkipped(false);
        fallback.setElapsedMs(0L);
        fallback.setInputSummary("agentReason=" + agent.getReasonCode());
        fallback.setOutputSummary("fallback to Query Engine V3");
        merged.add(fallback);
        if (v3.getStages() != null) merged.addAll(v3.getStages());
        for (int i = 0; i < merged.size(); i++) merged.get(i).setSeq(i + 1);
        v3.setStages(merged);
        v3.setExecutionMode("AGENTIC_V1_FALLBACK_V3");
    }
}
