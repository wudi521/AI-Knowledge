package cn.iocoder.yudao.module.evidence.service.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityFailureType;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ActivityRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlan;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlanner;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentPlanningDecision;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeExecutor;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeResult;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.NoProgressGuard;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanNode;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ProvenanceRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ReferenceRecord;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Online Public Agentic Knowledge Runtime.
 *
 * <p>Pipeline: immutable OriginalGoal -> Query Planning -> validated execution DAG -> Typed Tool Runtime
 * -> Activity/Reference/Provenance -> independent Goal Evaluation -> grounded Answer.</p>
 *
 * <p>本类不解释任何业务 intent。新增普通领域只能扩展 Domain/Tool Contract，不能在这里增加
 * LONGEST_TITLE、TITLE_CONTAINS、APPLICATION_NUMBER_LOOKUP 之类场景分支。</p>
 */
@Component
public class AgenticKnowledgeRuntimeEngine {
    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private final AgentExecutionPlanner planner;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentGoalEvaluator goalEvaluator;
    private final AnswerPipeline answerPipeline;
    private final EvidenceProperties properties;

    public AgenticKnowledgeRuntimeEngine(AgentExecutionPlanner planner,
                                         AgentRuntimeExecutor runtimeExecutor,
                                         AgentGoalEvaluator goalEvaluator,
                                         AnswerPipeline answerPipeline,
                                         EvidenceProperties properties) {
        this.planner = planner;
        this.runtimeExecutor = runtimeExecutor;
        this.goalEvaluator = goalEvaluator;
        this.answerPipeline = answerPipeline;
        this.properties = properties;
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history) {
        return execute(query, kbId, domainCode, tenantId, userId, traceId, history, List.of());
    }

    public Result execute(String query, Long kbId, String domainCode, Long tenantId, Long userId,
                          String traceId, List<ChatTurnDTO> history, List<Long> contextEntityIds) {
        AgentExecutionState state;
        try {
            state = new AgentExecutionState(query);
        } catch (IllegalArgumentException e) {
            return Result.stopped(AgentStopReason.INVALID_CAPABILITY_CALL, "查询不能为空。",
                    List.of(), 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        EvidenceProperties.Agent cfg = properties == null ? null : properties.getAgent();
        AgentExecutionBudget budget = cfg == null ? AgentExecutionBudget.defaults()
                : new AgentExecutionBudget(Math.max(1, cfg.getMaxSteps()), Math.max(1, cfg.getMaxLlmCalls()),
                Math.max(1L, cfg.getMaxElapsedMs()));
        LinkedHashSet<Long> trustedEntityIds = sanitizeIds(contextEntityIds);
        CapabilityInvocationContext context = new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), List.copyOf(trustedEntityIds),
                cfg == null ? "default" : cfg.getEnvironment(), cfg != null && cfg.isWriteAllowed());

        List<ChatTurnDTO> safeHistory = history == null ? List.of() : List.copyOf(history);
        List<AgentObservation> observations = new ArrayList<>();
        List<Evidence> gatheredEvidence = new ArrayList<>();
        List<String> deterministicAnswers = new ArrayList<>();
        List<AgentTraceStep> traceSteps = new ArrayList<>();
        List<ActivityRecord> activities = new ArrayList<>();
        List<ReferenceRecord> references = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();
        NoProgressGuard noProgressGuard = new NoProgressGuard();
        int replanAttempt = 0;

        while (true) {
            AgentStopReason planningBudgetStop = planningBudgetStop(state, budget);
            if (planningBudgetStop != null) {
                return stopped(state, planningBudgetStop, "规划预算已耗尽。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            int remainingNodes = budget.maxSteps() - state.getStep();
            state.incrementLlmCalls();
            long plannerStart = System.currentTimeMillis();
            AgentPlanningDecision planning = planner.plan(state, context, List.copyOf(observations),
                    List.copyOf(references), safeHistory, replanAttempt, remainingNodes);
            long plannerElapsed = System.currentTimeMillis() - plannerStart;
            if (planning == null) planning = AgentPlanningDecision.stop("规划器未返回有效计划。");
            traceSteps.add(trace(traceSteps, "QUERY_PLANNING", planning.action().name(), null,
                    state.getCurrentSubGoal(), null,
                    planning.action() == AgentPlanningDecision.Action.STOP ? "FAILED" : "SUCCEEDED",
                    plannerElapsed, StrUtil.maxLength(planning.message(), 400),
                    planning.action() == AgentPlanningDecision.Action.STOP ? AgentStopReason.CAPABILITY_UNAVAILABLE : null));

            if (planning.action() == AgentPlanningDecision.Action.NEED_MORE_INFO) {
                return clarify(state, planning.message(), gatheredEvidence, traceSteps, trustedEntityIds,
                        activities, references, provenance);
            }
            if (planning.action() == AgentPlanningDecision.Action.STOP) {
                return stopped(state, AgentStopReason.CAPABILITY_UNAVAILABLE,
                        StrUtil.blankToDefault(planning.message(), "当前能力不足以可靠完成该问题。"),
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }

            // Planner 的 ANSWER 只是建议，没有终止权。统一经过独立 Goal Evaluator；INSUFFICIENT 继续 bounded replan。
            if (planning.action() == AgentPlanningDecision.Action.ANSWER) {
                GoalCheck check = evaluateGoal(state, context, budget, observations,
                        deterministicAnswers, gatheredEvidence, traceSteps);
                if (check.budgetStop() != null) {
                    return stopped(state, check.budgetStop(), "目标充分性验证未能在预算内完成。",
                            gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
                }
                AgentGoalEvaluator.Evaluation evaluation = check.evaluation();
                if (evaluation.verdict() == AgentGoalEvaluator.Verdict.SATISFIED) {
                    return answer(state, safeHistory, evaluation, gatheredEvidence, deterministicAnswers, traceSteps,
                            trustedEntityIds, activities, references, provenance);
                }
                if (evaluation.verdict() == AgentGoalEvaluator.Verdict.NEED_MORE_INFO) {
                    return clarify(state, evaluation.message(), gatheredEvidence, traceSteps, trustedEntityIds,
                            activities, references, provenance);
                }
                if (evaluation.verdict() == AgentGoalEvaluator.Verdict.INSUFFICIENT
                        && replanAttempt < MAX_REPLAN_ATTEMPTS) {
                    replanAttempt++;
                    addGoalGapObservation(observations, state, evaluation, replanAttempt);
                    continue;
                }
                return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                        evaluation.verdict() == AgentGoalEvaluator.Verdict.EVALUATION_FAILED
                                ? "目标充分性验证失败，拒绝在未验证情况下输出答案。"
                                : "现有执行事实仍不足以完整回答原始问题。",
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }

            AgentExecutionPlan plan = planning.executionPlan();
            if (plan == null || !Objects.equals(state.getOriginalGoal(), plan.originalGoal())) {
                traceSteps.add(trace(traceSteps, "PLAN_VALIDATION", "EXECUTE_PLAN", null,
                        state.getOriginalGoal(), null, "FAILED", 0L,
                        "execution plan must bind immutable OriginalGoal", AgentStopReason.INVALID_CAPABILITY_CALL));
                return stopped(state, AgentStopReason.INVALID_CAPABILITY_CALL,
                        "执行计划未绑定 immutable OriginalGoal。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            if (noProgressGuard.repeatsInsufficient(plan)) {
                traceSteps.add(trace(traceSteps, "NO_PROGRESS_GUARD", "REJECT_REPEATED_PLAN", null,
                        state.getOriginalGoal(), planSummary(plan), "STOPPED", 0L,
                        "semantic execution plan already ran successfully and was insufficient",
                        AgentStopReason.NO_PROGRESS));
                return stopped(state, AgentStopReason.NO_PROGRESS,
                        "重新规划没有产生新的执行语义，停止重复访问同一数据源。",
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }

            String subGoal = plan.nodes().stream().map(PlanNode::purpose).filter(StrUtil::isNotBlank)
                    .reduce((a, b) -> a + " | " + b).orElse(state.getOriginalGoal());
            state.setCurrentSubGoal(StrUtil.maxLength(subGoal, 600));
            traceSteps.add(trace(traceSteps, "EXECUTION_PLAN", "EXECUTE_PLAN", null,
                    state.getCurrentSubGoal(), planSummary(plan), "SUCCEEDED", 0L,
                    "planId=" + plan.planId() + "; nodes=" + plan.nodes().size() + "; replanAttempt=" + replanAttempt,
                    null));

            long remainingMs = Math.max(1L, budget.maxElapsedMs() - state.elapsedMs());
            AgentExecutionBudget runtimeBudget = new AgentExecutionBudget(Math.max(1, remainingNodes),
                    Math.max(1, budget.maxLlmCalls() - state.getLlmCalls() + 1), remainingMs);
            AgentRuntimeResult runtime = runtimeExecutor.execute(plan, context, runtimeBudget);

            boolean planValid = !(runtime.status() == CapabilityResultStatus.FAILED
                    && runtime.failureType() == CapabilityFailureType.VALIDATION
                    && runtime.nodeResults().isEmpty());
            traceSteps.add(trace(traceSteps, "PLAN_VALIDATION", "EXECUTE_PLAN", null,
                    state.getCurrentSubGoal(), planSummary(plan), planValid ? "SUCCEEDED" : "FAILED", 0L,
                    planValid ? "schema/DAG validation passed" : StrUtil.maxLength(runtime.message(), 400),
                    planValid ? null : AgentStopReason.INVALID_CAPABILITY_CALL));
            if (!planValid) {
                if (replanAttempt < MAX_REPLAN_ATTEMPTS) {
                    replanAttempt++;
                    observations.add(AgentObservation.recoverableError("plan_validator", state.getCurrentSubGoal(),
                            StrUtil.blankToDefault(runtime.message(), "execution plan validation failed"),
                            "PLAN_VALIDATION:" + replanAttempt, AgentStopReason.INVALID_CAPABILITY_CALL,
                            Map.of("errorKind", "PLAN_VALIDATION", "failureType", "VALIDATION")));
                    continue;
                }
                return stopped(state, AgentStopReason.INVALID_CAPABILITY_CALL,
                        StrUtil.blankToDefault(runtime.message(), "执行计划验证失败。"), gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            for (ActivityRecord activity : runtime.activities()) {
                state.incrementStep();
                activities.add(activity);
                CapabilityResult nodeResult = runtime.nodeResults().get(activity.nodeId());
                AgentStopReason nodeStop = nodeResult == null ? null : nodeResult.stopReason();
                traceSteps.add(trace(traceSteps, "RUNTIME_EXECUTOR", "CALL_CAPABILITY", activity.capability(),
                        purpose(plan, activity.nodeId()), null,
                        activity.status() == CapabilityResultStatus.FAILED ? "FAILED" : activity.status().name(),
                        activity.elapsedMs(), runtimeActivitySummary(activity), nodeStop));
            }

            String resultIntegrityError = resultIntegrityError(runtime);
            traceSteps.add(trace(traceSteps, "RESULT_INTEGRITY", "VALIDATE", null, state.getCurrentSubGoal(), null,
                    resultIntegrityError == null ? "SUCCEEDED" : "FAILED", 0L,
                    resultIntegrityError == null ? "node results and activity records are consistent" : resultIntegrityError,
                    resultIntegrityError == null ? null : AgentStopReason.NO_RELIABLE_EVIDENCE));
            if (resultIntegrityError != null) {
                return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                        "Runtime 结果完整性验证失败。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            String provenanceError = provenanceIntegrityError(runtime);
            traceSteps.add(trace(traceSteps, "PROVENANCE_INTEGRITY", "VALIDATE", null, state.getCurrentSubGoal(), null,
                    provenanceError == null ? "SUCCEEDED" : "FAILED", 0L,
                    provenanceError == null ? "every ReferenceRecord is linked to provenance" : provenanceError,
                    provenanceError == null ? null : AgentStopReason.NO_RELIABLE_EVIDENCE));
            if (provenanceError != null) {
                return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                        "Reference / Provenance 完整性验证失败。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            references.addAll(runtime.references());
            provenance.addAll(runtime.provenance());
            materializeRuntimeFacts(plan, runtime, observations, gatheredEvidence, deterministicAnswers);

            boolean trustedChanged = false;
            for (ReferenceRecord reference : runtime.references()) {
                for (Long id : reference.verifiedEntityIds()) if (id != null) trustedChanged |= trustedEntityIds.add(id);
            }
            if (trustedChanged) context = context.withContextEntityIds(List.copyOf(trustedEntityIds));

            if (runtime.failed() && runtime.references().isEmpty()) {
                if (hasPlannerRecoverable(runtime) && replanAttempt < MAX_REPLAN_ATTEMPTS) {
                    replanAttempt++;
                    continue;
                }
                return stopped(state, stopReason(runtime.failureType()),
                        StrUtil.blankToDefault(runtime.message(), "执行计划失败。"), gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            GoalCheck check = evaluateGoal(state, context, budget, observations,
                    deterministicAnswers, gatheredEvidence, traceSteps);
            if (check.budgetStop() != null) {
                return stopped(state, check.budgetStop(), "目标充分性验证未能在预算内完成。",
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }
            AgentGoalEvaluator.Evaluation evaluation = check.evaluation();
            if (evaluation.verdict() == AgentGoalEvaluator.Verdict.SATISFIED) {
                return answer(state, safeHistory, evaluation, gatheredEvidence, deterministicAnswers, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }
            if (evaluation.verdict() == AgentGoalEvaluator.Verdict.NEED_MORE_INFO) {
                return clarify(state, evaluation.message(), gatheredEvidence, traceSteps, trustedEntityIds,
                        activities, references, provenance);
            }
            if (evaluation.verdict() == AgentGoalEvaluator.Verdict.INSUFFICIENT
                    && replanAttempt < MAX_REPLAN_ATTEMPTS) {
                // 只有确定性执行完成且不存在 transient/recoverable failure 时才把计划标记为“已证明不足”。
                // 运行时短暂故障仍允许 Planner 重新组织/重试，不被 no-progress 错杀。
                if (!hasPlannerRecoverable(runtime) && !hasRuntimeRetryable(runtime)) {
                    noProgressGuard.markInsufficient(plan);
                }
                replanAttempt++;
                addGoalGapObservation(observations, state, evaluation, replanAttempt);
                continue;
            }
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    evaluation.verdict() == AgentGoalEvaluator.Verdict.EVALUATION_FAILED
                            ? "目标充分性验证失败，拒绝在未验证情况下输出答案。"
                            : "现有执行事实仍不足以完整回答原始问题。",
                    gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
        }
    }

    private GoalCheck evaluateGoal(AgentExecutionState state,
                                   CapabilityInvocationContext context,
                                   AgentExecutionBudget budget,
                                   List<AgentObservation> observations,
                                   List<String> deterministicAnswers,
                                   List<Evidence> gatheredEvidence,
                                   List<AgentTraceStep> traceSteps) {
        if (goalEvaluator.consumesLlmCall()) {
            AgentStopReason budgetStop = llmBudgetStop(state, budget);
            if (budgetStop != null) return new GoalCheck(null, budgetStop);
            state.incrementLlmCalls();
        }
        long start = System.currentTimeMillis();
        AgentGoalEvaluator.Evaluation evaluation = goalEvaluator.evaluate(state.getOriginalGoal(),
                List.copyOf(observations), List.copyOf(deterministicAnswers), List.copyOf(gatheredEvidence), context);
        long elapsed = System.currentTimeMillis() - start;
        if (evaluation == null) evaluation = AgentGoalEvaluator.Evaluation.failed("goal evaluator returned null");
        AgentStopReason traceReason = switch (evaluation.verdict()) {
            case SATISFIED -> null;
            case NEED_MORE_INFO -> AgentStopReason.NEED_USER_INPUT;
            case INSUFFICIENT, EVALUATION_FAILED -> AgentStopReason.NO_RELIABLE_EVIDENCE;
        };
        String status = switch (evaluation.verdict()) {
            case SATISFIED -> "SUCCEEDED";
            case INSUFFICIENT -> "REPLAN";
            case NEED_MORE_INFO -> "STOPPED";
            case EVALUATION_FAILED -> "FAILED";
        };
        String frontier = evaluation.supportingReferenceIds().isEmpty()
                ? "" : "; proofFrontier=" + evaluation.supportingReferenceIds();
        traceSteps.add(trace(traceSteps, "RESULT_EVALUATION", "EVALUATE", null, state.getOriginalGoal(), null,
                status, elapsed, "verdict=" + evaluation.verdict() + frontier + "; "
                        + StrUtil.maxLength(evaluation.reason(), 400), traceReason));
        return new GoalCheck(evaluation, null);
    }

    private Result answer(AgentExecutionState state,
                          List<ChatTurnDTO> history,
                          AgentGoalEvaluator.Evaluation evaluation,
                          List<Evidence> gatheredEvidence,
                          List<String> deterministicAnswers,
                          List<AgentTraceStep> traceSteps,
                          LinkedHashSet<Long> trustedEntityIds,
                          List<ActivityRecord> activities,
                          List<ReferenceRecord> references,
                          List<ProvenanceRecord> provenance) {
        List<ReferenceRecord> proofReferences = proofReferences(evaluation, references);
        List<String> proofAnswers = proofReferences.isEmpty()
                ? distinct(deterministicAnswers) : deterministicAnswers(proofReferences);
        List<Evidence> proofEvidence = proofReferences.isEmpty()
                ? List.copyOf(gatheredEvidence) : evidences(proofReferences);
        String proofSummary = "proofFrontier=" + (evaluation == null ? List.of() : evaluation.supportingReferenceIds())
                + "; proofReferences=" + proofReferences.size();

        // 已被 Goal Evaluator 选入最终证明集的确定性 Tool 结果可以直接回答时，禁止因为历史上曾有语义 Evidence
        // 就再次进入昂贵的 Generate + Claim Verify。历史失败/候选证据仍保留在 trace/reference 中，但退出答案作用域。
        if (proofEvidence.isEmpty() && !proofAnswers.isEmpty()) {
            state.setEvidenceCoverage(EvidenceCoverage.FULL);
            state.stop(AgentStopReason.ENOUGH_EVIDENCE);
            traceSteps.add(trace(traceSteps, "ANSWER_VALIDATION", "ANSWER", null, state.getOriginalGoal(), null,
                    "SUCCEEDED", 0L, proofSummary + "; deterministicFastPath=true; answerPipelineSkipped=true",
                    AgentStopReason.ENOUGH_EVIDENCE));
            return new Result(State.ANSWER, String.join("\n", proofAnswers), null, AgentStopReason.ENOUGH_EVIDENCE,
                    List.of(), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), null,
                    List.copyOf(traceSteps), List.copyOf(trustedEntityIds), List.copyOf(activities),
                    List.copyOf(references), List.copyOf(provenance));
        }
        if (proofEvidence.isEmpty() || answerPipeline == null) {
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "独立 Goal Evaluator 虽通过，但最终证明集中没有可进入回答验证的事实输出。",
                    proofEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
        }

        long start = System.currentTimeMillis();
        GenerationResult generation = answerPipeline.generateWithClaims(state.getOriginalGoal(),
                List.copyOf(proofEvidence), history);
        long elapsed = System.currentTimeMillis() - start;
        if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
            traceSteps.add(trace(traceSteps, "ANSWER_VALIDATION", "ANSWER", null, state.getOriginalGoal(), null,
                    "FAILED", elapsed, proofSummary + "; answer failed claim/evidence validation",
                    AgentStopReason.NO_RELIABLE_EVIDENCE));
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "最终回答未通过证据/Claim 验证。", proofEvidence, traceSteps,
                    trustedEntityIds, activities, references, provenance);
        }
        String finalAnswer = proofAnswers.isEmpty() ? generation.getAnswer()
                : String.join("\n", proofAnswers) + "\n" + generation.getAnswer();
        state.setEvidenceCoverage(EvidenceCoverage.FULL);
        state.stop(AgentStopReason.ENOUGH_EVIDENCE);
        String timing = "; generateMs=" + generation.getGenerateMs()
                + "; verifyMs=" + generation.getVerifyMs()
                + "; generateCount=" + generation.getGenerateCount()
                + "; verifyCount=" + generation.getVerifyCount()
                + "; outcome=" + generation.getOutcome();
        traceSteps.add(trace(traceSteps, "ANSWER_VALIDATION", "ANSWER", null, state.getOriginalGoal(), null,
                "SUCCEEDED", elapsed, proofSummary + timing,
                AgentStopReason.ENOUGH_EVIDENCE));
        return new Result(State.ANSWER, finalAnswer, null, AgentStopReason.ENOUGH_EVIDENCE,
                List.copyOf(proofEvidence), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), generation,
                List.copyOf(traceSteps), List.copyOf(trustedEntityIds), List.copyOf(activities),
                List.copyOf(references), List.copyOf(provenance));
    }

    private List<ReferenceRecord> proofReferences(AgentGoalEvaluator.Evaluation evaluation,
                                                  List<ReferenceRecord> references) {
        if (references == null || references.isEmpty()) return List.of();
        List<String> selectedIds = evaluation == null ? List.of() : evaluation.supportingReferenceIds();
        // trustPlanner/迁移期 evaluator 没有 proof frontier 时保持旧兼容；生产 LLM evaluator v1.4 强制返回非空集合。
        if (selectedIds == null || selectedIds.isEmpty()) return List.copyOf(references);

        Map<String, ReferenceRecord> byId = new LinkedHashMap<>();
        for (ReferenceRecord reference : references) {
            if (reference != null && StrUtil.isNotBlank(reference.referenceId())) {
                byId.put(reference.referenceId(), reference);
            }
        }
        List<ReferenceRecord> out = new ArrayList<>();
        for (String id : selectedIds) {
            ReferenceRecord reference = byId.get(id);
            if (reference != null) out.add(reference);
        }
        return List.copyOf(out);
    }

    private List<String> deterministicAnswers(List<ReferenceRecord> proofReferences) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (proofReferences != null) {
            for (ReferenceRecord reference : proofReferences) {
                if (reference != null && StrUtil.isNotBlank(reference.deterministicAnswer())) {
                    out.add(reference.deterministicAnswer());
                }
            }
        }
        return List.copyOf(out);
    }

    private List<Evidence> evidences(List<ReferenceRecord> proofReferences) {
        List<Evidence> out = new ArrayList<>();
        if (proofReferences == null) return List.of();
        for (ReferenceRecord reference : proofReferences) {
            if (reference == null || reference.evidences() == null) continue;
            for (Evidence evidence : reference.evidences()) {
                if (evidence != null && !out.contains(evidence)) out.add(evidence);
            }
        }
        return List.copyOf(out);
    }

    private Result clarify(AgentExecutionState state,
                           String message,
                           List<Evidence> gatheredEvidence,
                           List<AgentTraceStep> traceSteps,
                           LinkedHashSet<Long> trustedEntityIds,
                           List<ActivityRecord> activities,
                           List<ReferenceRecord> references,
                           List<ProvenanceRecord> provenance) {
        state.stop(AgentStopReason.NEED_USER_INPUT);
        String question = StrUtil.blankToDefault(message, "请补充完成查询所需的关键信息。");
        traceSteps.add(trace(traceSteps, "STOP", "NEED_MORE_INFO", null, state.getOriginalGoal(), null,
                "STOPPED", 0L, StrUtil.maxLength(question, 400), AgentStopReason.NEED_USER_INPUT));
        return new Result(State.CLARIFY, null, question, AgentStopReason.NEED_USER_INPUT,
                List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), null,
                List.copyOf(traceSteps), List.copyOf(trustedEntityIds), List.copyOf(activities),
                List.copyOf(references), List.copyOf(provenance));
    }

    private void addGoalGapObservation(List<AgentObservation> observations,
                                       AgentExecutionState state,
                                       AgentGoalEvaluator.Evaluation evaluation,
                                       int replanAttempt) {
        String reason = StrUtil.blankToDefault(evaluation.reason(), "current facts do not satisfy OriginalGoal");
        observations.add(AgentObservation.recoverableError("goal_evaluator", state.getCurrentSubGoal(),
                StrUtil.maxLength(reason, 800), "GOAL_NOT_SATISFIED:" + replanAttempt + ":" + reason.hashCode(),
                AgentStopReason.NO_RELIABLE_EVIDENCE,
                Map.of("errorKind", "GOAL_NOT_SATISFIED", "verdict", "INSUFFICIENT",
                        "replanAttempt", replanAttempt)));
    }

    private void materializeRuntimeFacts(AgentExecutionPlan plan,
                                         AgentRuntimeResult runtime,
                                         List<AgentObservation> observations,
                                         List<Evidence> gatheredEvidence,
                                         List<String> deterministicAnswers) {
        for (ReferenceRecord reference : runtime.references()) {
            Map<String, Object> metadata = new LinkedHashMap<>(reference.metadata());
            metadata.put("resultStatus", reference.status().name());
            metadata.put("referenceId", reference.referenceId());
            observations.add(AgentObservation.success(reference.capability(), purpose(plan, reference.nodeId()),
                    StrUtil.maxLength(reference.summary(), 1200), reference.referenceId(), metadata));
            gatheredEvidence.addAll(reference.evidences());
            if (StrUtil.isNotBlank(reference.deterministicAnswer())) {
                deterministicAnswers.add(reference.deterministicAnswer());
            }
        }
        for (Map.Entry<String, CapabilityResult> entry : runtime.nodeResults().entrySet()) {
            CapabilityResult result = entry.getValue();
            if (result == null || result.success()) continue;
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            if (result.failureType() != null) metadata.put("failureType", result.failureType().name());
            metadata.putIfAbsent("errorKind", result.failureType() == null ? "FAILED" : result.failureType().name());
            if (result.recoverable()) {
                observations.add(AgentObservation.recoverableError(
                        capability(plan, entry.getKey()), purpose(plan, entry.getKey()),
                        StrUtil.maxLength(result.message(), 800), "PLAN_REPAIR:" + entry.getKey(),
                        result.stopReason(), metadata));
            } else {
                observations.add(new AgentObservation(capability(plan, entry.getKey()), purpose(plan, entry.getKey()),
                        StrUtil.maxLength(result.message(), 800), "FAILED:" + entry.getKey(), "ERROR",
                        false, false, false, result.stopReason() == null ? null : result.stopReason().name(), metadata));
            }
        }
    }

    private String resultIntegrityError(AgentRuntimeResult runtime) {
        if (runtime == null) return "runtime result is null";
        if (runtime.nodeResults().isEmpty()) return null;
        Map<String, ActivityRecord> activityByNode = new HashMap<>();
        for (ActivityRecord activity : runtime.activities()) {
            if (activity == null || StrUtil.isBlank(activity.nodeId())) return "activity record has no nodeId";
            if (activityByNode.putIfAbsent(activity.nodeId(), activity) != null) {
                return "duplicate activity record for node " + activity.nodeId();
            }
        }
        for (Map.Entry<String, CapabilityResult> entry : runtime.nodeResults().entrySet()) {
            ActivityRecord activity = activityByNode.get(entry.getKey());
            if (activity == null) return "missing activity record for node " + entry.getKey();
            CapabilityResult result = entry.getValue();
            if (result == null) return "null capability result for node " + entry.getKey();
            if (activity.status() != result.status()) return "activity/result status mismatch for node " + entry.getKey();
        }
        return null;
    }

    private String provenanceIntegrityError(AgentRuntimeResult runtime) {
        Map<String, ProvenanceRecord> provenanceByReference = new HashMap<>();
        for (ProvenanceRecord record : runtime.provenance()) {
            if (record == null || StrUtil.isBlank(record.referenceId())) return "provenance record has no referenceId";
            if (provenanceByReference.putIfAbsent(record.referenceId(), record) != null) {
                return "duplicate provenance for reference " + record.referenceId();
            }
        }
        Set<String> seenReferenceIds = new HashSet<>();
        for (ReferenceRecord reference : runtime.references()) {
            if (reference == null || StrUtil.isBlank(reference.referenceId())) return "reference has no referenceId";
            if (!seenReferenceIds.add(reference.referenceId())) return "duplicate referenceId " + reference.referenceId();
            ProvenanceRecord record = provenanceByReference.get(reference.referenceId());
            if (record == null) return "missing provenance for reference " + reference.referenceId();
            if (!Objects.equals(reference.planId(), record.planId())
                    || !Objects.equals(reference.nodeId(), record.nodeId())
                    || !Objects.equals(reference.capability(), record.capability())) {
                return "reference/provenance identity mismatch for " + reference.referenceId();
            }
            CapabilityResult nodeResult = runtime.nodeResults().get(reference.nodeId());
            if (nodeResult == null || !nodeResult.success()) {
                return "reference points to non-successful node " + reference.nodeId();
            }
            if (nodeResult.status() != reference.status()) {
                return "reference/result status mismatch for node " + reference.nodeId();
            }
        }
        if (provenanceByReference.size() != seenReferenceIds.size()) {
            return "orphan provenance record exists";
        }
        return null;
    }

    private boolean hasPlannerRecoverable(AgentRuntimeResult runtime) {
        for (CapabilityResult result : runtime.nodeResults().values()) {
            if (result != null && result.recoverable()) return true;
        }
        return false;
    }

    private boolean hasRuntimeRetryable(AgentRuntimeResult runtime) {
        for (CapabilityResult result : runtime.nodeResults().values()) {
            if (result != null && result.runtimeRetryable()) return true;
        }
        return false;
    }

    private AgentStopReason planningBudgetStop(AgentExecutionState state, AgentExecutionBudget budget) {
        if (state.elapsedMs() >= budget.maxElapsedMs()) return AgentStopReason.TIME_BUDGET_EXCEEDED;
        if (state.getStep() >= budget.maxSteps()) return AgentStopReason.MAX_STEPS;
        if (state.getLlmCalls() >= budget.maxLlmCalls()) return AgentStopReason.MAX_LLM_CALLS;
        return null;
    }

    private AgentStopReason llmBudgetStop(AgentExecutionState state, AgentExecutionBudget budget) {
        if (state.elapsedMs() >= budget.maxElapsedMs()) return AgentStopReason.TIME_BUDGET_EXCEEDED;
        if (state.getLlmCalls() >= budget.maxLlmCalls()) return AgentStopReason.MAX_LLM_CALLS;
        return null;
    }

    private AgentStopReason stopReason(CapabilityFailureType type) {
        if (type == null) return AgentStopReason.NO_RELIABLE_EVIDENCE;
        return switch (type) {
            case VALIDATION -> AgentStopReason.INVALID_CAPABILITY_CALL;
            case PERMISSION -> AgentStopReason.PERMISSION_DENIED;
            case CONFIGURATION -> AgentStopReason.CAPABILITY_UNAVAILABLE;
            case TIMEOUT -> AgentStopReason.TIME_BUDGET_EXCEEDED;
            case THROTTLED, TRANSIENT, DEPENDENCY, DATA_INCOMPLETE -> AgentStopReason.NO_RELIABLE_EVIDENCE;
        };
    }

    private Result stopped(AgentExecutionState state,
                           AgentStopReason reason,
                           String message,
                           List<Evidence> gatheredEvidence,
                           List<AgentTraceStep> traceSteps,
                           LinkedHashSet<Long> trustedEntityIds,
                           List<ActivityRecord> activities,
                           List<ReferenceRecord> references,
                           List<ProvenanceRecord> provenance) {
        if (!state.isStopped()) state.stop(reason);
        traceSteps.add(trace(traceSteps, "STOP", "STOP", null, state.getOriginalGoal(), null,
                "STOPPED", 0L, StrUtil.maxLength(message, 400), reason));
        return Result.stopped(reason, message, gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                List.copyOf(trustedEntityIds), activities, references, provenance);
    }

    private AgentTraceStep trace(List<AgentTraceStep> current,
                                 String phase,
                                 String action,
                                 String capability,
                                 String purpose,
                                 String argumentsSummary,
                                 String status,
                                 long elapsedMs,
                                 String summary,
                                 AgentStopReason stopReason) {
        return new AgentTraceStep(current.size() + 1, phase, action, capability,
                StrUtil.maxLength(purpose, 600), StrUtil.maxLength(argumentsSummary, 900), status,
                Math.max(0L, elapsedMs), StrUtil.maxLength(summary, 600), stopReason);
    }

    private String planSummary(AgentExecutionPlan plan) {
        try {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (PlanNode node : plan.nodes()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", node.id());
                item.put("capability", node.capability());
                item.put("dependsOn", node.dependsOn());
                nodes.add(item);
            }
            return StrUtil.maxLength(JSONUtil.toJsonStr(nodes), 900);
        } catch (Exception e) {
            return "nodes=" + plan.nodes().size();
        }
    }

    private String runtimeActivitySummary(ActivityRecord activity) {
        String failure = activity.failureType() == null ? "" : "; failureType=" + activity.failureType();
        return "status=" + activity.status() + failure + "; " + StrUtil.maxLength(activity.message(), 260);
    }

    private String capability(AgentExecutionPlan plan, String nodeId) {
        for (PlanNode node : plan.nodes()) if (Objects.equals(node.id(), nodeId)) return node.capability();
        return null;
    }

    private String purpose(AgentExecutionPlan plan, String nodeId) {
        for (PlanNode node : plan.nodes()) if (Objects.equals(node.id(), nodeId)) return node.purpose();
        return null;
    }

    private LinkedHashSet<Long> sanitizeIds(List<Long> ids) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        if (ids != null) for (Long id : ids) if (id != null) out.add(id);
        return out;
    }

    private List<String> distinct(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
    }

    private record GoalCheck(AgentGoalEvaluator.Evaluation evaluation, AgentStopReason budgetStop) {}

    public enum State {
        ANSWER,
        CLARIFY,
        STOPPED
    }

    public record Result(State state,
                         String answer,
                         String clarificationQuestion,
                         AgentStopReason stopReason,
                         List<Evidence> evidences,
                         int steps,
                         int llmCalls,
                         EvidenceCoverage evidenceCoverage,
                         GenerationResult generation,
                         List<AgentTraceStep> traceSteps,
                         List<Long> verifiedEntityIds,
                         List<ActivityRecord> activities,
                         List<ReferenceRecord> references,
                         List<ProvenanceRecord> provenance) {
        public Result {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            traceSteps = traceSteps == null ? List.of() : List.copyOf(traceSteps);
            verifiedEntityIds = verifiedEntityIds == null ? List.of() : List.copyOf(verifiedEntityIds);
            activities = activities == null ? List.of() : List.copyOf(activities);
            references = references == null ? List.of() : List.copyOf(references);
            provenance = provenance == null ? List.of() : List.copyOf(provenance);
        }

        public static Result stopped(AgentStopReason reason,
                                     String message,
                                     List<Evidence> evidences,
                                     int steps,
                                     int llmCalls,
                                     List<AgentTraceStep> traceSteps,
                                     List<Long> verifiedEntityIds,
                                     List<ActivityRecord> activities,
                                     List<ReferenceRecord> references,
                                     List<ProvenanceRecord> provenance) {
            return new Result(State.STOPPED, null, message, reason, evidences, steps, llmCalls,
                    EvidenceCoverage.NONE, null, traceSteps, verifiedEntityIds, activities, references, provenance);
        }
    }
}
