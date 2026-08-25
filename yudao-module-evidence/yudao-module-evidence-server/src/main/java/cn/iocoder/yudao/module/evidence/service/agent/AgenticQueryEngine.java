package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** V1.1 有界执行循环；能力通过统一输出协议接入，主循环不按业务能力增加分支。 */
@Component
public class AgenticQueryEngine {
    private final AgentPlanner planner;
    private final CapabilityInvoker capabilityInvoker;
    private final AnswerPipeline answerPipeline;
    private final EvidenceProperties properties;

    @Autowired
    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker,
                              AnswerPipeline answerPipeline, EvidenceProperties properties) {
        this.planner = planner; this.capabilityInvoker = capabilityInvoker;
        this.answerPipeline = answerPipeline; this.properties = properties;
    }

    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker, AnswerPipeline answerPipeline) {
        this(planner, capabilityInvoker, answerPipeline, new EvidenceProperties());
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history) {
        return execute(query, kbId, domainCode, tenantId, userId, traceId, history, List.of());
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history, List<Long> contextEntityIds) {
        List<AgentTraceStep> traceSteps = new ArrayList<>();
        AgentExecutionState state;
        try { state = new AgentExecutionState(query); }
        catch (IllegalArgumentException e) {
            traceSteps.add(trace(traceSteps, "GUARD", null, null, null, "FAILED", 0L,
                    "original goal is blank", AgentStopReason.INVALID_CAPABILITY_CALL));
            return Result.stopped(AgentStopReason.INVALID_CAPABILITY_CALL, "查询不能为空。", List.of(), 0, 0, traceSteps);
        }
        EvidenceProperties.Agent cfg = properties == null ? null : properties.getAgent();
        AgentExecutionBudget budget = cfg == null ? AgentExecutionBudget.defaults()
                : new AgentExecutionBudget(Math.max(1, cfg.getMaxSteps()), Math.max(1, cfg.getMaxLlmCalls()), Math.max(1L, cfg.getMaxElapsedMs()));
        AgentExecutionGuard guard = new AgentExecutionGuard(budget);
        CapabilityInvocationContext context = new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), contextEntityIds == null ? List.of() : contextEntityIds,
                cfg == null ? "default" : cfg.getEnvironment(), cfg != null && cfg.isWriteAllowed());
        List<AgentObservation> observations = new ArrayList<>();
        List<Evidence> gatheredEvidence = new ArrayList<>();
        List<String> deterministicAnswers = new ArrayList<>();

        while (!state.isStopped()) {
            AgentExecutionGuard.GuardResult plannerGuard = guard.beforePlannerCall(state);
            if (!plannerGuard.allowed()) {
                state.stop(plannerGuard.stopReason());
                traceSteps.add(trace(traceSteps, "GUARD", null, null, state.getCurrentSubGoal(), "STOPPED", 0L,
                        "planner call rejected by execution guard", plannerGuard.stopReason()));
                break;
            }
            state.incrementLlmCalls();
            long plannerStart = System.currentTimeMillis();
            AgentDecision decision = planner.decide(state, context, List.copyOf(observations), history);
            long plannerElapsed = System.currentTimeMillis() - plannerStart;
            if (decision == null) {
                state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                traceSteps.add(trace(traceSteps, "PLANNER", null, null, state.getCurrentSubGoal(), "FAILED", plannerElapsed,
                        "planner returned null decision", AgentStopReason.NO_RELIABLE_EVIDENCE));
                break;
            }
            traceSteps.add(trace(traceSteps, "PLANNER", decision.action().name(), decision.capability(), decision.purpose(),
                    "SUCCEEDED", plannerElapsed, StrUtil.maxLength(StrUtil.blankToDefault(decision.message(), "decision produced"), 300), null));

            switch (decision.action()) {
                case CALL_CAPABILITY -> {
                    CapabilityInvoker.PreparedCall call = capabilityInvoker.prepare(decision.capability(), decision.arguments(), context);
                    if (!call.accepted()) {
                        state.stop(call.stopReason());
                        traceSteps.add(trace(traceSteps, "CAPABILITY_PREPARE", decision.action().name(), decision.capability(),
                                decision.purpose(), "FAILED", 0L, StrUtil.maxLength(call.message(), 300), call.stopReason()));
                        return Result.stopped(call.stopReason(), call.message(), gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    AgentExecutionGuard.GuardResult callGuard = guard.beforeCapabilityCall(state, call.fingerprint());
                    if (!callGuard.allowed()) {
                        state.stop(callGuard.stopReason());
                        traceSteps.add(trace(traceSteps, "GUARD", decision.action().name(), decision.capability(), decision.purpose(),
                                "STOPPED", 0L, "capability call rejected by execution guard", callGuard.stopReason()));
                        return Result.stopped(callGuard.stopReason(), "执行预算或重复调用保护触发。", gatheredEvidence,
                                state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    state.addCapabilityCallFingerprint(call.fingerprint()); state.incrementStep();
                    state.setCurrentSubGoal(StrUtil.blankToDefault(decision.purpose(), state.getCurrentSubGoal()));
                    long capabilityStart = System.currentTimeMillis();
                    CapabilityResult capabilityResult = capabilityInvoker.invoke(call, context);
                    long capabilityElapsed = System.currentTimeMillis() - capabilityStart;
                    if (!capabilityResult.success()) {
                        AgentStopReason reason = capabilityResult.stopReason() == null ? AgentStopReason.NO_RELIABLE_EVIDENCE : capabilityResult.stopReason();
                        state.stop(reason);
                        traceSteps.add(trace(traceSteps, "CAPABILITY", decision.action().name(), decision.capability(), decision.purpose(),
                                "FAILED", capabilityElapsed, StrUtil.maxLength(capabilityResult.message(), 300), reason));
                        return Result.stopped(reason, capabilityResult.message(), gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    ObservationMaterial material = materialize(decision, capabilityResult);
                    traceSteps.add(trace(traceSteps, "CAPABILITY", decision.action().name(), decision.capability(), decision.purpose(),
                            "SUCCEEDED", capabilityElapsed, StrUtil.maxLength(material.observation().summary(), 500), null));
                    if (!state.markProgress(material.progressHash())) {
                        state.stop(AgentStopReason.NO_PROGRESS);
                        traceSteps.add(trace(traceSteps, "GUARD", decision.action().name(), decision.capability(), decision.purpose(),
                                "STOPPED", 0L, "capability produced no new progress", AgentStopReason.NO_PROGRESS));
                        return Result.stopped(AgentStopReason.NO_PROGRESS, "连续能力调用没有产生新的有效信息。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    observations.add(material.observation()); gatheredEvidence.addAll(material.evidences());
                    if (StrUtil.isNotBlank(material.deterministicAnswer())) deterministicAnswers.add(material.deterministicAnswer());
                }
                case ANSWER -> {
                    if (!deterministicAnswers.isEmpty()) {
                        state.setEvidenceCoverage(EvidenceCoverage.FULL); state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null, decision.purpose(),
                                "SUCCEEDED", 0L, "deterministic capability result answered original goal", AgentStopReason.ENOUGH_EVIDENCE));
                        return new Result(State.ANSWER, String.join("\n", deterministicAnswers), null, AgentStopReason.ENOUGH_EVIDENCE,
                                List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), null,
                                List.copyOf(traceSteps));
                    }
                    if (gatheredEvidence.isEmpty()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null, decision.purpose(),
                                "FAILED", 0L, "no reliable evidence to answer", AgentStopReason.NO_RELIABLE_EVIDENCE));
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "没有可靠证据支持回答。",
                                List.of(), state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    long answerStart = System.currentTimeMillis();
                    GenerationResult generation = answerPipeline.generateWithClaims(state.getOriginalGoal(), gatheredEvidence, history);
                    long answerElapsed = System.currentTimeMillis() - answerStart;
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null, decision.purpose(),
                                "FAILED", answerElapsed, "answer failed evidence/claim validation", AgentStopReason.NO_RELIABLE_EVIDENCE));
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "最终回答未通过证据验证。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
                    }
                    state.setEvidenceCoverage(EvidenceCoverage.FULL); state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                    traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null, decision.purpose(),
                            "SUCCEEDED", answerElapsed, "grounded answer passed claim validation", AgentStopReason.ENOUGH_EVIDENCE));
                    return new Result(State.ANSWER, generation.getAnswer(), null, AgentStopReason.ENOUGH_EVIDENCE,
                            List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), generation,
                            List.copyOf(traceSteps));
                }
                case NEED_MORE_INFO -> {
                    state.stop(AgentStopReason.NEED_USER_INPUT);
                    traceSteps.add(trace(traceSteps, "STOP", decision.action().name(), null, decision.purpose(),
                            "STOPPED", 0L, StrUtil.maxLength(decision.message(), 300), AgentStopReason.NEED_USER_INPUT));
                    return new Result(State.CLARIFY, null, StrUtil.blankToDefault(decision.message(), "请补充完成该查询所需的信息。"),
                            AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                            state.getEvidenceCoverage(), null, List.copyOf(traceSteps));
                }
                case STOP -> {
                    state.stop(AgentStopReason.CAPABILITY_UNAVAILABLE);
                    traceSteps.add(trace(traceSteps, "STOP", decision.action().name(), null, decision.purpose(),
                            "STOPPED", 0L, StrUtil.maxLength(decision.message(), 300), AgentStopReason.CAPABILITY_UNAVAILABLE));
                    return Result.stopped(AgentStopReason.CAPABILITY_UNAVAILABLE,
                            StrUtil.blankToDefault(decision.message(), "当前能力不足以可靠完成该问题。"),
                            gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
                }
            }
        }
        AgentStopReason reason = state.getStopReason() == null ? AgentStopReason.NO_RELIABLE_EVIDENCE : state.getStopReason();
        return Result.stopped(reason, "执行已停止。", gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps);
    }

    private AgentTraceStep trace(List<AgentTraceStep> current, String phase, String action, String capability,
                                 String purpose, String status, long elapsedMs, String summary, AgentStopReason stopReason) {
        return new AgentTraceStep(current.size() + 1, phase, action, capability, StrUtil.maxLength(purpose, 300),
                status, Math.max(0L, elapsedMs), StrUtil.maxLength(summary, 500), stopReason);
    }

    private ObservationMaterial materialize(AgentDecision decision, CapabilityResult result) {
        Object data = result.data();
        if (data instanceof AgentCapabilityOutput output) {
            List<Evidence> evidences = output.evidences() == null ? List.of() : output.evidences();
            String progress = decision.capability() + ":" + StrUtil.blankToDefault(output.progressHash(), "EMPTY");
            String summary = StrUtil.maxLength(StrUtil.blankToDefault(output.summary(), String.valueOf(result.metadata())), 1200);
            return new ObservationMaterial(new AgentObservation(decision.capability(), decision.purpose(), summary, progress),
                    evidences, progress, output.deterministicAnswer());
        }
        String summary = StrUtil.maxLength(String.valueOf(result.metadata()), 1200);
        String progress = decision.capability() + ":" + Integer.toHexString((String.valueOf(data) + summary).hashCode());
        return new ObservationMaterial(new AgentObservation(decision.capability(), decision.purpose(), summary, progress), List.of(), progress, null);
    }

    private record ObservationMaterial(AgentObservation observation, List<Evidence> evidences,
                                       String progressHash, String deterministicAnswer) { }

    public enum State { ANSWER, CLARIFY, STOPPED }

    public record Result(State state, String answer, String clarificationQuestion, AgentStopReason stopReason,
                         List<Evidence> evidences, int steps, int llmCalls, EvidenceCoverage evidenceCoverage,
                         GenerationResult generation, List<AgentTraceStep> traceSteps) {
        static Result stopped(AgentStopReason reason, String message, List<Evidence> evidences, int steps, int llmCalls,
                              List<AgentTraceStep> traceSteps) {
            return new Result(State.STOPPED, null, message, reason, evidences == null ? List.of() : List.copyOf(evidences),
                    steps, llmCalls, EvidenceCoverage.NONE, null, traceSteps == null ? List.of() : List.copyOf(traceSteps));
        }
    }
}
