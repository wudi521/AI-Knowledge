package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeRetrievalCapability;
import cn.iocoder.yudao.module.evidence.service.agent.capability.SimilarFieldValuesCapability;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V1.1 有界执行循环。能力通过 registry 增量接入，主循环不按业务问题增加 Intent/if。
 */
@Component
public class AgenticQueryEngine {
    private final AgentPlanner planner;
    private final CapabilityInvoker capabilityInvoker;
    private final AnswerPipeline answerPipeline;

    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker, AnswerPipeline answerPipeline) {
        this.planner = planner;
        this.capabilityInvoker = capabilityInvoker;
        this.answerPipeline = answerPipeline;
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history) {
        AgentExecutionState state;
        try {
            state = new AgentExecutionState(query);
        } catch (IllegalArgumentException e) {
            return Result.stopped(AgentStopReason.INVALID_CAPABILITY_CALL, "查询不能为空。", List.of(), 0, 0);
        }
        AgentExecutionGuard guard = new AgentExecutionGuard(AgentExecutionBudget.defaults());
        CapabilityInvocationContext context = new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId);
        List<AgentObservation> observations = new ArrayList<>();
        List<Evidence> gatheredEvidence = new ArrayList<>();
        List<String> deterministicAnswers = new ArrayList<>();

        while (!state.isStopped()) {
            AgentExecutionGuard.GuardResult plannerGuard = guard.beforePlannerCall(state);
            if (!plannerGuard.allowed()) {
                state.stop(plannerGuard.stopReason());
                break;
            }
            state.incrementLlmCalls();
            AgentDecision decision = planner.decide(state, context, List.copyOf(observations), history);
            if (decision == null) {
                state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                break;
            }

            switch (decision.action()) {
                case CALL_CAPABILITY -> {
                    CapabilityInvoker.PreparedCall call = capabilityInvoker.prepare(decision.capability(), decision.arguments(), context);
                    if (!call.accepted()) {
                        state.stop(call.stopReason());
                        return Result.stopped(call.stopReason(), call.message(), gatheredEvidence, state.getStep(), state.getLlmCalls());
                    }
                    AgentExecutionGuard.GuardResult callGuard = guard.beforeCapabilityCall(state, call.fingerprint());
                    if (!callGuard.allowed()) {
                        state.stop(callGuard.stopReason());
                        return Result.stopped(callGuard.stopReason(), "执行预算或重复调用保护触发。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls());
                    }
                    state.addCapabilityCallFingerprint(call.fingerprint());
                    state.incrementStep();
                    state.setCurrentSubGoal(StrUtil.blankToDefault(decision.purpose(), state.getCurrentSubGoal()));
                    CapabilityResult capabilityResult = capabilityInvoker.invoke(call, context);
                    if (!capabilityResult.success()) {
                        AgentStopReason reason = capabilityResult.stopReason() == null
                                ? AgentStopReason.NO_RELIABLE_EVIDENCE : capabilityResult.stopReason();
                        state.stop(reason);
                        return Result.stopped(reason, capabilityResult.message(), gatheredEvidence,
                                state.getStep(), state.getLlmCalls());
                    }
                    ObservationMaterial material = materialize(decision, capabilityResult);
                    if (!state.markProgress(material.progressHash())) {
                        state.stop(AgentStopReason.NO_PROGRESS);
                        return Result.stopped(AgentStopReason.NO_PROGRESS, "连续能力调用没有产生新的有效信息。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls());
                    }
                    observations.add(material.observation());
                    gatheredEvidence.addAll(material.evidences());
                    if (StrUtil.isNotBlank(material.deterministicAnswer())) {
                        deterministicAnswers.add(material.deterministicAnswer());
                    }
                }
                case ANSWER -> {
                    if (!deterministicAnswers.isEmpty()) {
                        state.setEvidenceCoverage(EvidenceCoverage.FULL);
                        state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                        return new Result(State.ANSWER, String.join("\n", deterministicAnswers), null,
                                AgentStopReason.ENOUGH_EVIDENCE, gatheredEvidence,
                                state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage());
                    }
                    if (gatheredEvidence.isEmpty()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "没有可靠证据支持回答。",
                                List.of(), state.getStep(), state.getLlmCalls());
                    }
                    GenerationResult generation = answerPipeline.generateWithClaims(state.getOriginalGoal(), gatheredEvidence, history);
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "最终回答未通过证据验证。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls());
                    }
                    state.setEvidenceCoverage(EvidenceCoverage.FULL);
                    state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                    return new Result(State.ANSWER, generation.getAnswer(), null, AgentStopReason.ENOUGH_EVIDENCE,
                            gatheredEvidence, state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage());
                }
                case NEED_MORE_INFO -> {
                    state.stop(AgentStopReason.NEED_USER_INPUT);
                    return new Result(State.CLARIFY, null,
                            StrUtil.blankToDefault(decision.message(), "请补充完成该查询所需的信息。"),
                            AgentStopReason.NEED_USER_INPUT, gatheredEvidence, state.getStep(), state.getLlmCalls(),
                            state.getEvidenceCoverage());
                }
                case STOP -> {
                    state.stop(AgentStopReason.CAPABILITY_UNAVAILABLE);
                    return Result.stopped(AgentStopReason.CAPABILITY_UNAVAILABLE,
                            StrUtil.blankToDefault(decision.message(), "当前能力不足以可靠完成该问题。"),
                            gatheredEvidence, state.getStep(), state.getLlmCalls());
                }
            }
        }
        return Result.stopped(state.getStopReason() == null ? AgentStopReason.NO_RELIABLE_EVIDENCE : state.getStopReason(),
                "执行已停止。", gatheredEvidence, state.getStep(), state.getLlmCalls());
    }

    private ObservationMaterial materialize(AgentDecision decision, CapabilityResult result) {
        Object data = result.data();
        if (data instanceof KnowledgeRetrievalCapability.Output output) {
            List<Evidence> evidences = output.evidences() == null ? List.of() : output.evidences();
            String progress = decision.capability() + ":" + output.progressHash();
            AgentObservation observation = new AgentObservation(decision.capability(), decision.purpose(), output.summary(), progress);
            return new ObservationMaterial(observation, evidences, progress, null);
        }
        if (data instanceof SimilarFieldValuesCapability.Output output) {
            String progress = decision.capability() + ":" + output.progressHash();
            AgentObservation observation = new AgentObservation(decision.capability(), decision.purpose(), output.summary(), progress);
            return new ObservationMaterial(observation, List.of(), progress, output.directAnswer());
        }
        String summary = StrUtil.maxLength(String.valueOf(result.metadata()), 1200);
        String progress = decision.capability() + ":" + Integer.toHexString((String.valueOf(data) + summary).hashCode());
        return new ObservationMaterial(new AgentObservation(decision.capability(), decision.purpose(), summary, progress),
                List.of(), progress, null);
    }

    private record ObservationMaterial(AgentObservation observation, List<Evidence> evidences,
                                       String progressHash, String deterministicAnswer) { }

    public enum State { ANSWER, CLARIFY, STOPPED }

    public record Result(State state, String answer, String clarificationQuestion, AgentStopReason stopReason,
                         List<Evidence> evidences, int steps, int llmCalls, EvidenceCoverage evidenceCoverage) {
        static Result stopped(AgentStopReason reason, String message, List<Evidence> evidences, int steps, int llmCalls) {
            return new Result(State.STOPPED, null, message, reason,
                    evidences == null ? List.of() : List.copyOf(evidences), steps, llmCalls, EvidenceCoverage.NONE);
        }
    }
}
