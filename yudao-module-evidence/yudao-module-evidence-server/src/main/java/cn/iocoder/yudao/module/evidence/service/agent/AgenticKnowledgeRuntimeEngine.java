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
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanNode;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ProvenanceRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ReferenceRecord;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * <p>No business intent enum is interpreted here. Adding a normal domain must extend Domain/Tool contracts,
 * not add branches such as LONGEST_TITLE or APPLICATION_NUMBER_LOOKUP to this class.</p>
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
        LinkedHashSet<Long> trustedEntityIds = new LinkedHashSet<>();
        if (contextEntityIds != null) {
            for (Long id : contextEntityIds) if (id != null) trustedEntityIds.add(id);
        }
        CapabilityInvocationContext context = new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), List.copyOf(trustedEntityIds),
                cfg == null ? "default" : cfg.getEnvironment(), cfg != null && cfg.isWriteAllowed());

        List<ChatTurnDTO> safeHistory = history == null ? List.of() : history;
        List<AgentObservation> observations = new ArrayList<>();
        List<Evidence> gatheredEvidence = new ArrayList<>();
        List<String> deterministicAnswers = new ArrayList<>();
        List<AgentTraceStep> traceSteps = new ArrayList<>();
        List<ActivityRecord> activities = new ArrayList<>();
        List<ReferenceRecord> references = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();
        int replanAttempt = 0;

        while (true) {
            AgentStopReason budgetStop = budgetStop(state, budget, true);
            if (budgetStop != null) {
                return stopped(state, budgetStop, "执行预算已耗尽。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

            int remainingNodes = budget.maxSteps() - state.getStep();
            if (remainingNodes <= 0) {
                return stopped(state, AgentStopReason.MAX_STEPS, "执行步骤预算已耗尽。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }

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
                state.stop(AgentStopReason.NEED_USER_INPUT);
                return new Result(State.CLARIFY, null,
                        StrUtil.blankToDefault(planning.message(), "请补充完成查询所需的信息。"),
                        AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                        state.getEvidenceCoverage(), null, List.copyOf(traceSteps), trusted(trustedEntityIds),
                        List.copyOf(activities), List.copyOf(references), List.copyOf(provenance));
            }
            if (planning.action() == AgentPlanningDecision.Action.STOP) {
                return stopped(state, AgentStopReason.CAPABILITY_UNAVAILABLE,
                        StrUtil.blankToDefault(planning.message(), "当前能力不足以可靠完成该问题。"),
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }
            if (planning.action() == AgentPlanningDecision.Action.ANSWER) {
                return evaluateAndMaybeAnswer(state, context, budget, safeHistory, observations,
                        gatheredEvidence, deterministicAnswers, traceSteps, trustedEntityIds,
                        activities, references, provenance, replanAttempt);
            }

            AgentExecutionPlan plan = planning.executionPlan();
            if (plan == null || !Objects.equals(state.getOriginalGoal(), plan.originalGoal())) {
                return stopped(state, AgentStopReason.INVALID_CAPABILITY_CALL,
                        "执行计划未绑定 immutable OriginalGoal。", gatheredEvidence, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }
            String subGoal = plan.nodes().stream().map(PlanNode::purpose).filter(StrUtil::isNotBlank)
                    .reduce((a, b) -> a + " | " + b).orElse(state.getOriginalGoal());
            state.setCurrentSubGoal(StrUtil.maxLength(subGoal, 600));
            traceSteps.add(trace(traceSteps, "PLAN_VALIDATION", "EXECUTE_PLAN", null,
                    state.getCurrentSubGoal(), planSummary(plan), "SUCCEEDED", 0L,
                    "planId=" + plan.planId() + "; nodes=" + plan.nodes().size() + "; replanAttempt=" + replanAttempt, null));

            long remainingMs = Math.max(1L, budget.maxElapsedMs() - state.elapsedMs());
            AgentExecutionBudget runtimeBudget = new AgentExecutionBudget(remainingNodes,
                    Math.max(1, budget.maxLlmCalls() - state.getLlmCalls() + 1), remainingMs);
            AgentRuntimeResult runtime = runtimeExecutor.execute(plan, context, runtimeBudget);
            for (ActivityRecord activity : runtime.activities()) {
                state.incrementStep();
                activities.add(activity);
                CapabilityResult nodeResult = runtime.nodeResults().get(activity.nodeId());
                AgentStopReason nodeStop = nodeResult == null ? null : nodeResult.stopReason();
                traceSteps.add(trace(traceSteps, "RUNTIME_EXECUTOR", "CALL_CAPABILITY", activity.capability(),
                        purpose(plan, activity.nodeId()), null,
                        activity.status() == CapabilityResultStatus.FAILED ? "FAILED" : "SUCCEEDED",
                        activity.elapsedMs(), runtimeActivitySummary(activity), nodeStop));
            }
            references.addAll(runtime.references());
            provenance.addAll(runtime.provenance());
            materializeRuntimeFacts(plan, runtime, observations, gatheredEvidence, deterministicAnswers);

            boolean trustedChanged = false;
            for (ReferenceRecord reference : runtime.references()) {
                for (Long id : reference.verifiedEntityIds()) if (id != null) trustedChanged |= trustedEntityIds.add(id);
            }
            if (trustedChanged) context = context.withContextEntityIds(trusted(trustedEntityIds));

            if (runtime.failed() && runtime.references().isEmpty()) {
                if (hasPlannerRecoverable(runtime) && replanAttempt < MAX_REPLAN_ATTEMPTS) {
                    replanAttempt++;
                    continue;
                }
                AgentStopReason reason = stopReason(runtime.failureType());
                return stopped(state, reason, StrUtil.blankToDefault(runtime.message(), "执行计划失败。"),
                        gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
            }

            EvaluationOutcome outcome = evaluateGoal(state, context, budget, observations,
                    deterministicAnswers, gatheredEvidence, traceSteps);
            if (outcome.result() != null) {
                Result terminal = outcome.result();
                return terminal.withRuntimeRecords(activities, references, provenance, trustedEntityIds);
            }
            if (outcome.evaluation().verdict() == AgentGoalEvaluator.Verdict.SATISFIED) {
                return answer(state, safeHistory, gatheredEvidence, deterministicAnswers, traceSteps,
                        trustedEntityIds, activities, references, provenance);
            }
            if (outcome.evaluation().verdict() == AgentGoalEvaluator.Verdict.NEED_MORE_INFO) {
                state.stop(AgentStopReason.NEED_USER_INPUT);
                return new Result(State.CLARIFY, null,
                        StrUtil.blankToDefault(outcome.evaluation().message(), "请补充问题中的关键信息。"),
                        AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                        state.getEvidenceCoverage(), null, List.copyOf(traceSteps), trusted(trustedEntityIds),
                        List.copyOf(activities), List.copyOf(references), List.copyOf(provenance));
            }
            if (outcome.evaluation().verdict() == AgentGoalEvaluator.Verdict.INSUFFICIENT
                    && replanAttempt < MAX_REPLAN_ATTEMPTS) {
                replanAttempt++;
                observations.add(AgentObservation.recoverableError("goal_evaluator", state.getCurrentSubGoal(),
                        StrUtil.maxLength(outcome.evaluation().reason(), 800),
                        "GOAL_NOT_SATISFIED:" + replanAttempt,
                        AgentStopReason.NO_RELIABLE_EVIDENCE,
                        Map.of("errorKind", "GOAL_NOT_SATISFIED", "verdict", "INSUFFICIENT")));
                continue;
            }
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "现有执行事实仍不足以完整回答原始问题。", gatheredEvidence, traceSteps,
                    trustedEntityIds, activities, references, provenance);
        }
    }

    private Result evaluateAndMaybeAnswer(AgentExecutionState state,
                                          CapabilityInvocationContext context,
                                          AgentExecutionBudget budget,
                                          List<ChatTurnDTO> history,
                                          List<AgentObservation> observations,
                                          List<Evidence> gatheredEvidence,
                                          List<String> deterministicAnswers,
                                          List<AgentTraceStep> traceSteps,
                                          LinkedHashSet<Long> trustedEntityIds,
                                          List<ActivityRecord> activities,
                                          List<ReferenceRecord> references,
                                          List<ProvenanceRecord> provenance,
                                          int replanAttempt) {
        if (references.isEmpty() && gatheredEvidence.isEmpty() && deterministicAnswers.isEmpty()) {
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE, "没有执行事实支持回答。",
                    gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
        }
        EvaluationOutcome outcome = evaluateGoal(state, context, budget, observations,
                deterministicAnswers, gatheredEvidence, traceSteps);
        if (outcome.result() != null) return outcome.result().withRuntimeRecords(activities, references, provenance, trustedEntityIds);
        if (outcome.evaluation().verdict() == AgentGoalEvaluator.Verdict.SATISFIED) {
            return answer(state, history, gatheredEvidence, deterministicAnswers, traceSteps,
                    trustedEntityIds, activities, references, provenance);
        }
        if (outcome.evaluation().verdict() == AgentGoalEvaluator.Verdict.NEED_MORE_INFO) {
            state.stop(AgentStopReason.NEED_USER_INPUT);
            return new Result(State.CLARIFY, null,
                    StrUtil.blankToDefault(outcome.evaluation().message(), "请补充问题中的关键信息。"),
                    AgentStopReason.NEED_USER_INPUT, List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(),
                    state.getEvidenceCoverage(), null, List.copyOf(traceSteps), trusted(trustedEntityIds),
                    List.copyOf(activities), List.copyOf(references), List.copyOf(provenance));
        }
        return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                replanAttempt >= MAX_REPLAN_ATTEMPTS ? "现有事实不足以完整回答原始问题。" : "Planner 请求回答但独立目标验证未通过。",
                gatheredEvidence, traceSteps, trustedEntityIds, activities, references, provenance);
    }

    private EvaluationOutcome evaluateGoal(AgentExecutionState state,
                                           CapabilityInvocationContext context,
                                           AgentExecutionBudget budget,
                                           List<AgentObservation> observations,
                                           List<String> deterministicAnswers,
                                           List<Evidence> gatheredEvidence,
                                           List<AgentTraceStep> traceSteps) {
        if (goalEvaluator.consumesLlmCall()) {
            AgentStopReason budgetStop = budgetStop(state, budget, true);
            if (budgetStop != null) {
                return new EvaluationOutcome(null,
                        Result.stopped(budgetStop, "目标充分性验证未能在预算内完成。",
                                gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                                List.of(), List.of(), List.of(), List.of()));
            }
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
        String status = evaluation.verdict() == AgentGoalEvaluator.Verdict.SATISFIED ? "SUCCEEDED"
                : evaluation.verdict() == AgentGoalEvaluator.Verdict.INSUFFICIENT ? "REPLAN"
                : evaluation.verdict() == AgentGoalEvaluator.Verdict.NEED_MORE_INFO ? "STOPPED" : "FAILED";
        traceSteps.add(trace(traceSteps, "RESULT_EVALUATION", "EVALUATE", null, state.getOriginalGoal(), null,
                status, elapsed, "verdict=" + evaluation.verdict() + "; " + StrUtil.maxLength(evaluation.reason(), 400),
                traceReason));
        if (evaluation.verdict() == AgentGoalEvaluator.Verdict.EVALUATION_FAILED) {
            return new EvaluationOutcome(evaluation,
                    Result.stopped(AgentStopReason.NO_RELIABLE_EVIDENCE,
                            "目标充分性验证失败，拒绝在未验证情况下输出答案。",
                            gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                            List.of(), List.of(), List.of(), List.of()));
        }
        return new EvaluationOutcome(evaluation, null);
    }

    private Result answer(AgentExecutionState state,
                          List<ChatTurnDTO> history,
                          List<Evidence> gatheredEvidence,
                          List<String> deterministicAnswers,
                          List<AgentTraceStep> traceSteps,
                          LinkedHashSet<Long> trustedEntityIds,
                          List<ActivityRecord> activities,
                          List<ReferenceRecord> references,
                          List<ProvenanceRecord> provenance) {
        if (gatheredEvidence.isEmpty() && !deterministicAnswers.isEmpty()) {
            state.setEvidenceCoverage(EvidenceCoverage.FULL);
            state.stop(AgentStopReason.ENOUGH_EVIDENCE);
            String answer = String.join("\n", distinct(deterministicAnswers));
            traceSteps.add(trace(traceSteps, "ANSWER_VALIDATION", "ANSWER", null, state.getOriginalGoal(), null,
                    "SUCCEEDED", 0L, "deterministic references satisfy immutable OriginalGoal",
                    AgentStopReason.ENOUGH_EVIDENCE));
            return new Result(State.ANSWER, answer, null, AgentStopReason.ENOUGH_EVIDENCE,
                    List.of(), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), null,
                    List.copyOf(traceSteps), trusted(trustedEntityIds), List.copyOf(activities),
                    List.copyOf(references), List.copyOf(provenance));
        }
        if (gatheredEvidence.isEmpty() || answerPipeline == null) {
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "没有可用于最终回答验证的证据。", gatheredEvidence, traceSteps,
                    trustedEntityIds, activities, references, provenance);
        }
        long start = System.currentTimeMillis();
        GenerationResult generation = answerPipeline.generateWithClaims(state.getOriginalGoal(),
                List.copyOf(gatheredEvidence), history);
        long elapsed = System.currentTimeMillis() - start;
        if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
            return stopped(state, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "最终回答未通过证据/Claim 验证。", gatheredEvidence, traceSteps,
                    trustedEntityIds, activities, references, provenance);
        }
        String finalAnswer = deterministicAnswers.isEmpty() ? generation.getAnswer()
                : String.join("\n", distinct(deterministicAnswers)) + "\n" + generation.getAnswer();
        state.setEvidenceCoverage(EvidenceCoverage.FULL);
        state.stop(AgentStopReason.ENOUGH_EVIDENCE);
        traceSteps.add(trace(traceSteps, "ANSWER_VALIDATION", "ANSWER", null, state.getOriginalGoal(), null,
                "SUCCEEDED", elapsed, "immutable OriginalGoal passed goal evaluation + claim/evidence validation",
                AgentStopReason.ENOUGH_EVIDENCE));
        return new Result(State.ANSWER, finalAnswer, null, AgentStopReason.ENOUGH_EVIDENCE,
                List.copyOf(gatheredEvidence), state.getStep(), state.getLlmCalls(), state.getEvidenceCoverage(), generation,
                List.copyOf(traceSteps), trusted(trustedEntityIds), List.copyOf(activities),
                List.copyOf(references), List.copyOf(provenance));
    }

    private void materializeRuntimeFacts(AgentExecutionPlan plan,
                                         AgentRuntimeResult runtime,
                                         List<AgentObservation> observations,
                                         List<Evidence> gatheredEvidence,
                                         List<String> deterministicAnswers) {
        for (ReferenceRecord reference : runtime.references()) {
            Map<String, Object> meta = reference.metadata();
            observations.add(AgentObservation.success(reference.capability(), purpose(plan, reference.nodeId()),
                    StrUtil.maxLength(reference.summary(), 1200), reference.referenceId(), meta));
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

    private boolean hasPlannerRecoverable(AgentRuntimeResult runtime) {
        for (CapabilityResult result : runtime.nodeResults().values()) {
            if (result != null && result.recoverable()) return true;
        }
        return false;
    }

    private AgentStopReason budgetStop(AgentExecutionState state, AgentExecutionBudget budget, boolean llmRequired) {
        if (state.elapsedMs() >= budget.maxElapsedMs()) return AgentStopReason.TIME_BUDGET_EXCEEDED;
        if (state.getStep() >= budget.maxSteps()) return AgentStopReason.MAX_STEPS;
        if (llmRequired && state.getLlmCalls() >= budget.maxLlmCalls()) return AgentStopReason.MAX_LLM_CALLS;
        return null;
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
        state.stop(reason);
        traceSteps.add(trace(traceSteps, "STOP", "STOP", null, state.getOriginalGoal(), null,
                "STOPPED", 0L, StrUtil.maxLength(message, 400), reason));
        return Result.stopped(reason, message, gatheredEvidence, state.getStep(), state.getLlmCalls(), traceSteps,
                trusted(trustedEntityIds), activities, references, provenance);
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

    private String planSummary(AgentExecutionPlan plan) {
        try {
            return StrUtil.maxLength(JSONUtil.toJsonStr(plan.nodes().stream().map(node -> Map.of(
                    "id", node.id(), "capability", node.capability(), "dependsOn", node.dependsOn())).toList()), 900);
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

    private List<Long> trusted(LinkedHashSet<Long> trustedEntityIds) {
        return trustedEntityIds == null ? List.of() : List.copyOf(trustedEntityIds);
    }

    private List<String> distinct(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
    }

    private record EvaluationOutcome(AgentGoalEvaluator.Evaluation evaluation, Result result) {}

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

        public Result withRuntimeRecords(List<ActivityRecord> newActivities,
                                         List<ReferenceRecord> newReferences,
                                         List<ProvenanceRecord> newProvenance,
                                         LinkedHashSet<Long> trustedEntityIds) {
            return new Result(state, answer, clarificationQuestion, stopReason, evidences, steps, llmCalls,
                    evidenceCoverage, generation, traceSteps,
                    trustedEntityIds == null ? verifiedEntityIds : List.copyOf(trustedEntityIds),
                    newActivities, newReferences, newProvenance);
        }
    }
}
