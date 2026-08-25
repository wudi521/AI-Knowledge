package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V1.1 有界执行循环；能力通过统一输出协议接入，主循环不按业务能力增加分支。 */
@Component
public class AgenticQueryEngine {
    private static final int MAX_RECOVERABLE_CAPABILITY_ERRORS = 2;
    private static final int MAX_GOAL_REJECTIONS = 2;

    private final AgentPlanner planner;
    private final CapabilityInvoker capabilityInvoker;
    private final AnswerPipeline answerPipeline;
    private final EvidenceProperties properties;
    private final AgentGoalEvaluator goalEvaluator;

    @Autowired
    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker,
                              AnswerPipeline answerPipeline, EvidenceProperties properties,
                              AgentGoalEvaluator goalEvaluator) {
        this.planner = planner;
        this.capabilityInvoker = capabilityInvoker;
        this.answerPipeline = answerPipeline;
        this.properties = properties;
        this.goalEvaluator = goalEvaluator;
    }

    /** 兼容旧单测/非 Spring 构造；正式 Spring 运行使用独立 Goal Evaluator。 */
    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker,
                              AnswerPipeline answerPipeline, EvidenceProperties properties) {
        this(planner, capabilityInvoker, answerPipeline, properties, AgentGoalEvaluator.trustPlanner());
    }

    public AgenticQueryEngine(AgentPlanner planner, CapabilityInvoker capabilityInvoker, AnswerPipeline answerPipeline) {
        this(planner, capabilityInvoker, answerPipeline, new EvidenceProperties(), AgentGoalEvaluator.trustPlanner());
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history) {
        return execute(query, kbId, domainCode, tenantId, userId, traceId, history, List.of());
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history, List<Long> contextEntityIds) {
        List<AgentTraceStep> traceSteps = new ArrayList<>();
        AgentExecutionState state;
        try {
            state = new AgentExecutionState(query);
        } catch (IllegalArgumentException e) {
            traceSteps.add(trace(traceSteps, "GUARD", null, null, null, null, "FAILED", 0L,
                    "evidenceCount=0; original goal is blank", AgentStopReason.INVALID_CAPABILITY_CALL));
            return Result.stopped(AgentStopReason.INVALID_CAPABILITY_CALL, "查询不能为空。", List.of(),
                    0, 0, traceSteps, List.of());
        }

        EvidenceProperties.Agent cfg = properties == null ? null : properties.getAgent();
        AgentExecutionBudget budget = cfg == null ? AgentExecutionBudget.defaults()
                : new AgentExecutionBudget(Math.max(1, cfg.getMaxSteps()), Math.max(1, cfg.getMaxLlmCalls()),
                Math.max(1L, cfg.getMaxElapsedMs()));
        AgentExecutionGuard guard = new AgentExecutionGuard(budget);
        LinkedHashSet<Long> trustedEntityIds = new LinkedHashSet<>();
        if (contextEntityIds != null) {
            for (Long id : contextEntityIds) if (id != null) trustedEntityIds.add(id);
        }
        CapabilityInvocationContext context = new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), List.copyOf(trustedEntityIds),
                cfg == null ? "default" : cfg.getEnvironment(), cfg != null && cfg.isWriteAllowed());
        List<AgentObservation> observations = new ArrayList<>();
        List<Evidence> gatheredEvidence = new ArrayList<>();
        List<String> deterministicAnswers = new ArrayList<>();
        int recoverableErrors = 0;
        int goalRejections = 0;

        while (!state.isStopped()) {
            AgentExecutionGuard.GuardResult plannerGuard = guard.beforePlannerCall(state);
            if (!plannerGuard.allowed()) {
                state.stop(plannerGuard.stopReason());
                traceSteps.add(trace(traceSteps, "GUARD", null, null, state.getCurrentSubGoal(), null, "STOPPED", 0L,
                        "evidenceCount=" + gatheredEvidence.size() + "; planner call rejected by execution guard",
                        plannerGuard.stopReason()));
                break;
            }

            state.incrementLlmCalls();
            long plannerStart = System.currentTimeMillis();
            AgentDecision decision = planner.decide(state, context, List.copyOf(observations), history);
            long plannerElapsed = System.currentTimeMillis() - plannerStart;
            if (decision == null) {
                state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                traceSteps.add(trace(traceSteps, "PLANNER", null, null, state.getCurrentSubGoal(), null, "FAILED",
                        plannerElapsed, "evidenceCount=" + gatheredEvidence.size() + "; planner returned null decision",
                        AgentStopReason.NO_RELIABLE_EVIDENCE));
                break;
            }
            String decisionArgs = argumentsSummary(decision.arguments());
            traceSteps.add(trace(traceSteps, "PLANNER", decision.action().name(), decision.capability(),
                    decision.purpose(), decisionArgs, "SUCCEEDED", plannerElapsed,
                    StrUtil.maxLength(StrUtil.blankToDefault(decision.message(), "decision produced"), 300), null));

            switch (decision.action()) {
                case CALL_CAPABILITY -> {
                    CapabilityInvoker.PreparedCall call = capabilityInvoker.prepare(decision.capability(), decision.arguments(), context);
                    if (!call.accepted()) {
                        if (call.recoverable() && recoverableErrors < MAX_RECOVERABLE_CAPABILITY_ERRORS) {
                            recoverableErrors++;
                            String progress = "RECOVERABLE_PREPARE_ERROR:" + decision.capability() + ":"
                                    + Integer.toHexString((String.valueOf(call.message()) + decisionArgs).hashCode());
                            state.markProgress(progress);
                            observations.add(AgentObservation.recoverableError(
                                    decision.capability(), decision.purpose(),
                                    StrUtil.maxLength(call.message(), 800), progress,
                                    call.stopReason(), Map.of("errorKind", "PREPARE_CONTRACT")));
                            traceSteps.add(trace(traceSteps, "CAPABILITY_PREPARE", decision.action().name(), decision.capability(),
                                    decision.purpose(), decisionArgs, "RETRYABLE", 0L,
                                    "evidenceCount=0; recoverableError=" + recoverableErrors + "/"
                                            + MAX_RECOVERABLE_CAPABILITY_ERRORS + "; "
                                            + StrUtil.maxLength(call.message(), 320), call.stopReason()));
                            continue;
                        }
                        state.stop(call.stopReason());
                        traceSteps.add(trace(traceSteps, "CAPABILITY_PREPARE", decision.action().name(), decision.capability(),
                                decision.purpose(), decisionArgs, "FAILED", 0L,
                                "evidenceCount=" + gatheredEvidence.size() + "; " + StrUtil.maxLength(call.message(), 260),
                                call.stopReason()));
                        return Result.stopped(call.stopReason(), call.message(), gatheredEvidence,
                                state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }
                    String safeArgs = argumentsSummary(call.arguments());
                    AgentExecutionGuard.GuardResult callGuard = guard.beforeCapabilityCall(state, call.fingerprint());
                    if (!callGuard.allowed()) {
                        if (callGuard.stopReason() == AgentStopReason.REPEATED_CALL
                                && recoverableErrors < MAX_RECOVERABLE_CAPABILITY_ERRORS) {
                            recoverableErrors++;
                            String progress = "EQUIVALENT_PLAN:" + StrUtil.nullToEmpty(call.fingerprint());
                            state.markProgress(progress);
                            String message = "equivalent execution plan was already executed; choose a materially different "
                                    + "operator/transform/filter/aggregate/orderBy, or answer/clarify/stop from existing observations";
                            observations.add(AgentObservation.recoverableError(
                                    decision.capability(), decision.purpose(), message, progress,
                                    AgentStopReason.REPEATED_CALL,
                                    Map.of("errorKind", "EQUIVALENT_PLAN")));
                            traceSteps.add(trace(traceSteps, "CAPABILITY_PREPARE", decision.action().name(), decision.capability(),
                                    decision.purpose(), safeArgs, "RETRYABLE", 0L,
                                    "evidenceCount=" + gatheredEvidence.size() + "; recoverableError="
                                            + recoverableErrors + "/" + MAX_RECOVERABLE_CAPABILITY_ERRORS
                                            + "; equivalent normalized execution plan blocked before capability execution",
                                    AgentStopReason.REPEATED_CALL));
                            continue;
                        }
                        state.stop(callGuard.stopReason());
                        traceSteps.add(trace(traceSteps, "GUARD", decision.action().name(), decision.capability(),
                                decision.purpose(), safeArgs, "STOPPED", 0L,
                                "evidenceCount=" + gatheredEvidence.size() + "; capability call rejected by execution guard",
                                callGuard.stopReason()));
                        return Result.stopped(callGuard.stopReason(), "执行预算或重复调用保护触发。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }
                    state.addCapabilityCallFingerprint(call.fingerprint());
                    state.incrementStep();
                    state.setCurrentSubGoal(StrUtil.blankToDefault(decision.purpose(), state.getCurrentSubGoal()));

                    long capabilityStart = System.currentTimeMillis();
                    CapabilityResult capabilityResult = capabilityInvoker.invoke(call, context);
                    long capabilityElapsed = System.currentTimeMillis() - capabilityStart;
                    if (!capabilityResult.success()) {
                        AgentStopReason reason = capabilityResult.stopReason() == null
                                ? AgentStopReason.NO_RELIABLE_EVIDENCE : capabilityResult.stopReason();
                        if (capabilityResult.recoverable() && recoverableErrors < MAX_RECOVERABLE_CAPABILITY_ERRORS) {
                            recoverableErrors++;
                            String progress = "RECOVERABLE_ERROR:" + decision.capability() + ":"
                                    + Integer.toHexString((String.valueOf(capabilityResult.message())
                                    + capabilityResult.metadata()).hashCode());
                            state.markProgress(progress);
                            AgentObservation errorObservation = AgentObservation.recoverableError(
                                    decision.capability(), decision.purpose(),
                                    StrUtil.maxLength(capabilityResult.message(), 800), progress, reason,
                                    capabilityResult.metadata());
                            observations.add(errorObservation);
                            traceSteps.add(trace(traceSteps, "CAPABILITY", decision.action().name(), decision.capability(),
                                    decision.purpose(), safeArgs, "RETRYABLE", capabilityElapsed,
                                    "evidenceCount=0; recoverableError=" + recoverableErrors + "/"
                                            + MAX_RECOVERABLE_CAPABILITY_ERRORS + "; "
                                            + StrUtil.maxLength(capabilityResult.message(), 320), reason));
                            continue;
                        }
                        state.stop(reason);
                        traceSteps.add(trace(traceSteps, "CAPABILITY", decision.action().name(), decision.capability(),
                                decision.purpose(), safeArgs, "FAILED", capabilityElapsed,
                                "evidenceCount=0; " + StrUtil.maxLength(capabilityResult.message(), 260), reason));
                        return Result.stopped(reason, capabilityResult.message(), gatheredEvidence,
                                state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }
                    ObservationMaterial material = materialize(decision, capabilityResult);
                    traceSteps.add(trace(traceSteps, "CAPABILITY", decision.action().name(), decision.capability(),
                            decision.purpose(), safeArgs, "SUCCEEDED", capabilityElapsed,
                            "evidenceCount=" + material.evidences().size()
                                    + "; verifiedEntityCount=" + material.verifiedEntityIds().size()
                                    + "; " + StrUtil.maxLength(material.observation().summary(), 400), null));
                    if (!state.markProgress(material.progressHash())) {
                        state.stop(AgentStopReason.NO_PROGRESS);
                        traceSteps.add(trace(traceSteps, "GUARD", decision.action().name(), decision.capability(),
                                decision.purpose(), safeArgs, "STOPPED", 0L,
                                "evidenceCount=" + gatheredEvidence.size() + "; capability produced no new progress",
                                AgentStopReason.NO_PROGRESS));
                        return Result.stopped(AgentStopReason.NO_PROGRESS, "连续能力调用没有产生新的有效信息。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }
                    observations.add(material.observation());
                    gatheredEvidence.addAll(material.evidences());
                    if (StrUtil.isNotBlank(material.deterministicAnswer())) {
                        deterministicAnswers.add(material.deterministicAnswer());
                    }
                    if (material.verifiedEntityIds() != null && !material.verifiedEntityIds().isEmpty()) {
                        boolean changed = false;
                        for (Long id : material.verifiedEntityIds()) {
                            if (id != null) changed |= trustedEntityIds.add(id);
                        }
                        if (changed) {
                            context = context.withContextEntityIds(trusted(trustedEntityIds));
                            traceSteps.add(trace(traceSteps, "TRUSTED_SCOPE", decision.action().name(), decision.capability(),
                                    decision.purpose(), safeArgs, "SUCCEEDED", 0L,
                                    "evidenceCount=" + gatheredEvidence.size() + "; verifiedEntityIds=" + trustedEntityIds, null));
                        }
                    }
                }
                case ANSWER -> {
                    if (gatheredEvidence.isEmpty() && deterministicAnswers.isEmpty()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null,
                                decision.purpose(), decisionArgs, "FAILED", 0L,
                                "evidenceCount=0; no reliable evidence to answer", AgentStopReason.NO_RELIABLE_EVIDENCE));
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "没有可靠证据支持回答。",
                                List.of(), state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }

                    if (goalEvaluator.consumesLlmCall()) {
                        AgentExecutionGuard.GuardResult evaluatorGuard = guard.beforePlannerCall(state);
                        if (!evaluatorGuard.allowed()) {
                            state.stop(evaluatorGuard.stopReason());
                            traceSteps.add(trace(traceSteps, "GOAL_EVALUATOR", decision.action().name(), null,
                                    decision.purpose(), null, "STOPPED", 0L,
                                    "goal evaluation rejected by shared model/time budget", evaluatorGuard.stopReason()));
                            return Result.stopped(evaluatorGuard.stopReason(), "目标充分性验证未能在预算内完成。",
                                    gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                                    trusted(trustedEntityIds));
                        }
                        state.incrementLlmCalls();
                    }

                    long evaluatorStart = System.currentTimeMillis();
                    AgentGoalEvaluator.Evaluation evaluation = goalEvaluator.evaluate(
                            state.getOriginalGoal(), List.copyOf(observations), List.copyOf(deterministicAnswers),
                            List.copyOf(gatheredEvidence), context);
                    long evaluatorElapsed = System.currentTimeMillis() - evaluatorStart;
                    AgentGoalEvaluator.Verdict verdict = evaluation == null
                            ? AgentGoalEvaluator.Verdict.EVALUATION_FAILED : evaluation.verdict();
                    String evaluatorReason = evaluation == null ? "goal evaluator returned null"
                            : StrUtil.blankToDefault(evaluation.reason(), "goal evaluation completed");
                    String evaluatorStatus = verdict == AgentGoalEvaluator.Verdict.SATISFIED ? "SUCCEEDED"
                            : verdict == AgentGoalEvaluator.Verdict.INSUFFICIENT ? "RETRYABLE"
                            : verdict == AgentGoalEvaluator.Verdict.NEED_MORE_INFO ? "STOPPED" : "FAILED";
                    traceSteps.add(trace(traceSteps, "GOAL_EVALUATOR", decision.action().name(), null,
                            decision.purpose(), null, evaluatorStatus, evaluatorElapsed,
                            "verdict=" + verdict + "; " + StrUtil.maxLength(evaluatorReason, 380),
                            verdict == AgentGoalEvaluator.Verdict.SATISFIED ? null
                                    : verdict == AgentGoalEvaluator.Verdict.NEED_MORE_INFO
                                    ? AgentStopReason.NEED_USER_INPUT : AgentStopReason.NO_RELIABLE_EVIDENCE));

                    if (verdict == AgentGoalEvaluator.Verdict.NEED_MORE_INFO) {
                        state.stop(AgentStopReason.NEED_USER_INPUT);
                        return new Result(State.CLARIFY, null,
                                evaluation == null ? "请补充问题中的关键信息。"
                                        : StrUtil.blankToDefault(evaluation.message(), "请补充问题中的关键信息。"),
                                AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence),
                                state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), null,
                                List.copyOf(traceSteps), trusted(trustedEntityIds));
                    }
                    if (verdict == AgentGoalEvaluator.Verdict.EVALUATION_FAILED) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE,
                                "目标充分性验证失败，拒绝在未验证情况下输出答案。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                                trusted(trustedEntityIds));
                    }
                    if (verdict == AgentGoalEvaluator.Verdict.INSUFFICIENT) {
                        if (goalRejections < MAX_GOAL_REJECTIONS) {
                            goalRejections++;
                            String progress = "GOAL_NOT_SATISFIED:"
                                    + Integer.toHexString((evaluatorReason + observations.size()
                                    + deterministicAnswers.size() + gatheredEvidence.size()).hashCode());
                            if (!state.markProgress(progress)) {
                                state.stop(AgentStopReason.NO_PROGRESS);
                                return Result.stopped(AgentStopReason.NO_PROGRESS,
                                        "目标充分性验证连续未产生新的缺口信息。", gatheredEvidence,
                                        state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                            }
                            observations.add(AgentObservation.recoverableError(
                                    "goal_evaluator", decision.purpose(), StrUtil.maxLength(evaluatorReason, 800), progress,
                                    AgentStopReason.NO_RELIABLE_EVIDENCE,
                                    Map.of("errorKind", "GOAL_NOT_SATISFIED", "verdict", "INSUFFICIENT")));
                            continue;
                        }
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE,
                                "现有执行事实仍不足以完整回答原始问题。", gatheredEvidence,
                                state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }

                    if (gatheredEvidence.isEmpty()) {
                        state.setEvidenceCoverage(EvidenceCoverage.FULL);
                        state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null,
                                decision.purpose(), decisionArgs, "SUCCEEDED", 0L,
                                "evidenceCount=0; verifiedEntityCount=" + trustedEntityIds.size()
                                        + "; independent goal evaluator accepted deterministic result",
                                AgentStopReason.ENOUGH_EVIDENCE));
                        return new Result(State.ANSWER, String.join("\n", deterministicAnswers), null,
                                AgentStopReason.ENOUGH_EVIDENCE, List.of(), state.getStep(), state.getLlmCalls(),
                                state.getEvidenceCoverage(), null, List.copyOf(traceSteps), trusted(trustedEntityIds));
                    }

                    // currentSubGoal 只用于规划；最终生成永远回答 immutable originalGoal。
                    String generationQuery = state.getOriginalGoal();
                    long answerStart = System.currentTimeMillis();
                    GenerationResult generation = answerPipeline.generateWithClaims(generationQuery, gatheredEvidence, history);
                    long answerElapsed = System.currentTimeMillis() - answerStart;
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        state.stop(AgentStopReason.NO_RELIABLE_EVIDENCE);
                        traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null,
                                decision.purpose(), decisionArgs, "FAILED", answerElapsed,
                                "evidenceCount=" + gatheredEvidence.size() + "; answer failed evidence/claim validation",
                                AgentStopReason.NO_RELIABLE_EVIDENCE));
                        return Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE, "最终回答未通过证据验证。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                    }
                    String finalAnswer = deterministicAnswers.isEmpty()
                            ? generation.getAnswer()
                            : String.join("\n", deterministicAnswers) + "\n" + generation.getAnswer();
                    state.setEvidenceCoverage(EvidenceCoverage.FULL);
                    state.stop(AgentStopReason.ENOUGH_EVIDENCE);
                    traceSteps.add(trace(traceSteps, "ANSWER", decision.action().name(), null,
                            decision.purpose(), decisionArgs, "SUCCEEDED", answerElapsed,
                            "evidenceCount=" + gatheredEvidence.size() + "; independent goal evaluation + claim validation passed",
                            AgentStopReason.ENOUGH_EVIDENCE));
                    return new Result(State.ANSWER, finalAnswer, null, AgentStopReason.ENOUGH_EVIDENCE,
                            List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                            state.getEvidenceCoverage(), generation, List.copyOf(traceSteps), trusted(trustedEntityIds));
                }
                case NEED_MORE_INFO -> {
                    state.stop(AgentStopReason.NEED_USER_INPUT);
                    traceSteps.add(trace(traceSteps, "STOP", decision.action().name(), null,
                            decision.purpose(), decisionArgs, "STOPPED", 0L,
                            "evidenceCount=" + gatheredEvidence.size() + "; " + StrUtil.maxLength(decision.message(), 260),
                            AgentStopReason.NEED_USER_INPUT));
                    return new Result(State.CLARIFY, null,
                            StrUtil.blankToDefault(decision.message(), "请补充完成该查询所需的信息。"),
                            AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                            state.getEvidenceCoverage(), null, List.copyOf(traceSteps), trusted(trustedEntityIds));
                }
                case STOP -> {
                    state.stop(AgentStopReason.CAPABILITY_UNAVAILABLE);
                    traceSteps.add(trace(traceSteps, "STOP", decision.action().name(), null,
                            decision.purpose(), decisionArgs, "STOPPED", 0L,
                            "evidenceCount=" + gatheredEvidence.size() + "; " + StrUtil.maxLength(decision.message(), 260),
                            AgentStopReason.CAPABILITY_UNAVAILABLE));
                    return Result.stopped(AgentStopReason.CAPABILITY_UNAVAILABLE,
                            StrUtil.blankToDefault(decision.message(), "当前能力不足以可靠完成该问题。"),
                            gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps, trusted(trustedEntityIds));
                }
            }
        }
        AgentStopReason reason = state.getStopReason() == null ? AgentStopReason.NO_RELIABLE_EVIDENCE : state.getStopReason();
        return Result.stopped(reason, "执行已停止。", gatheredEvidence, state.getStep(), state.getLlmCalls(),
                traceSteps, trusted(trustedEntityIds));
    }

    private AgentTraceStep trace(List<AgentTraceStep> current, String phase, String action, String capability,
                                 String purpose, String argumentsSummary, String status, long elapsedMs, String summary,
                                 AgentStopReason stopReason) {
        return new AgentTraceStep(current.size() + 1, phase, action, capability,
                StrUtil.maxLength(purpose, 300), StrUtil.maxLength(argumentsSummary, 700),
                status, Math.max(0L, elapsedMs), StrUtil.maxLength(summary, 500), stopReason);
    }

    private String argumentsSummary(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return null;
        try { return StrUtil.maxLength(JSONUtil.toJsonStr(arguments), 700); }
        catch (Exception e) { return StrUtil.maxLength(String.valueOf(arguments), 700); }
    }

    private ObservationMaterial materialize(AgentDecision decision, CapabilityResult result) {
        Object data = result.data();
        if (data instanceof AgentCapabilityOutput output) {
            List<Evidence> evidences = output.evidences() == null ? List.of() : output.evidences();
            List<Long> verified = sanitizeVerifiedEntityIds(output.verifiedEntityIds(), result);
            String progress = decision.capability() + ":" + StrUtil.blankToDefault(output.progressHash(), "EMPTY");
            String summary = StrUtil.maxLength(StrUtil.blankToDefault(output.summary(), String.valueOf(result.metadata())), 1200);
            AgentObservation observation = AgentObservation.success(decision.capability(), decision.purpose(), summary,
                    progress, result.metadata());
            return new ObservationMaterial(observation, evidences, progress, output.deterministicAnswer(), verified);
        }
        String summary = StrUtil.maxLength(String.valueOf(result.metadata()), 1200);
        String progress = decision.capability() + ":" + Integer.toHexString((String.valueOf(data) + summary).hashCode());
        return new ObservationMaterial(AgentObservation.success(decision.capability(), decision.purpose(), summary,
                progress, result.metadata()), List.of(), progress, null, List.of());
    }

    /**
     * trusted scope 的最后一道通用防线。
     * 聚合/计数/分组和值投影事实只能作为 provenance，不能把参与计算或代表性来源实体升级成后续“它/这些”的指代范围。
     */
    private List<Long> sanitizeVerifiedEntityIds(List<Long> rawIds, CapabilityResult result) {
        if (rawIds == null || rawIds.isEmpty()) return List.of();
        List<Long> ids = rawIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return List.of();
        Object valueProjectionRaw = result == null ? null : result.metadata().get("valueProjection");
        if (Boolean.TRUE.equals(valueProjectionRaw)
                || (valueProjectionRaw != null && "true".equalsIgnoreCase(String.valueOf(valueProjectionRaw)))) {
            return List.of();
        }
        Object taskRaw = result == null ? null : result.metadata().get("task");
        String task = taskRaw == null ? "" : String.valueOf(taskRaw).trim();
        if ("COUNT".equalsIgnoreCase(task) || "AGGREGATE".equalsIgnoreCase(task)
                || "GROUP".equalsIgnoreCase(task)) return List.of();
        Integer outputCount = metadataCount(result == null ? null : result.metadata().get("outputCount"));
        if (outputCount != null && outputCount >= 0 && outputCount != ids.size()) return List.of();
        return ids;
    }

    private Integer metadataCount(Object raw) {
        if (raw instanceof Number n) return Math.max(0, n.intValue());
        if (raw == null) return null;
        try { return Math.max(0, Integer.parseInt(String.valueOf(raw))); }
        catch (Exception ignore) { return null; }
    }

    private List<Long> trusted(LinkedHashSet<Long> entityIds) {
        return entityIds == null || entityIds.isEmpty() ? List.of() : List.copyOf(entityIds);
    }

    private record ObservationMaterial(AgentObservation observation, List<Evidence> evidences,
                                       String progressHash, String deterministicAnswer,
                                       List<Long> verifiedEntityIds) { }

    public enum State { ANSWER, CLARIFY, STOPPED }

    public record Result(State state, String answer, String clarificationQuestion, AgentStopReason stopReason,
                         List<Evidence> evidences, int steps, int llmCalls, EvidenceCoverage evidenceCoverage,
                         GenerationResult generation, List<AgentTraceStep> traceSteps,
                         List<Long> verifiedEntityIds) {
        static Result stopped(AgentStopReason reason, String message, List<Evidence> evidences, int steps, int llmCalls,
                              List<AgentTraceStep> traceSteps, List<Long> verifiedEntityIds) {
            return new Result(State.STOPPED, null, message, reason,
                    evidences == null ? List.of() : List.copyOf(evidences), steps, llmCalls,
                    EvidenceCoverage.NONE, null, traceSteps == null ? List.of() : List.copyOf(traceSteps),
                    verifiedEntityIds == null ? List.of() : List.copyOf(verifiedEntityIds));
        }
    }
}
