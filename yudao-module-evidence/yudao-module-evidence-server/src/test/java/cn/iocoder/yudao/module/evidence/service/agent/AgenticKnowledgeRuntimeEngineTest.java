package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlan;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlanner;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentPlanningDecision;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeExecutor;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticKnowledgeRuntimeEngineTest {

    @Test
    void validatedDagFlowsThroughEvaluatorAndAnswerWithProvenance() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new DeterministicCapability("fact", "公布号=CN123")), List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) ->
                    AgentPlanningDecision.execute(new AgentExecutionPlan("plan-1", state.getOriginalGoal(), replanAttempt,
                            List.of(new PlanNode("n1", "fact", Map.of(), "查询确定性事实", Set.of()))));
            AgentGoalEvaluator evaluator = noCostEvaluator(AgentGoalEvaluator.Evaluation.satisfied("事实完整覆盖原始目标"));
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "申请号X的公布号是什么？", 6L, "PATENT", 1L, 2L, "trace-runtime-1", List.of());

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("公布号=CN123", result.answer());
            assertEquals(1, result.activities().size());
            assertEquals(1, result.references().size());
            assertEquals(1, result.provenance().size());
            assertEquals("trace-runtime-1", result.provenance().get(0).traceId());
            assertTrue(result.traceSteps().stream().anyMatch(s -> "QUERY_PLANNING".equals(s.phase())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "PLAN_VALIDATION".equals(s.phase()) && "SUCCEEDED".equals(s.status())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "RESULT_INTEGRITY".equals(s.phase()) && "SUCCEEDED".equals(s.status())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "PROVENANCE_INTEGRITY".equals(s.phase()) && "SUCCEEDED".equals(s.status())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "RUNTIME_EXECUTOR".equals(s.phase())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "RESULT_EVALUATION".equals(s.phase())));
            assertTrue(result.traceSteps().stream().anyMatch(s -> "ANSWER_VALIDATION".equals(s.phase())));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void insufficientEvaluationTriggersBoundedReplanInsteadOfRuntimeRetry() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger evaluatorCalls = new AtomicInteger();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new SummaryOnlyCapability(), new DeterministicCapability("strong-fact", "最终答案")), List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) -> {
                int call = plannerCalls.getAndIncrement();
                String capability = call == 0 ? "weak-fact" : "strong-fact";
                return AgentPlanningDecision.execute(new AgentExecutionPlan("plan-" + call, state.getOriginalGoal(), replanAttempt,
                        List.of(new PlanNode("n" + call, capability, Map.of(),
                                call == 0 ? "取得相关但不足的事实" : "直接补足 evaluator 指出的证明缺口", Set.of()))));
            };
            AgentGoalEvaluator evaluator = new AgentGoalEvaluator() {
                @Override
                public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                           List<String> deterministicAnswers,
                                           List<Evidence> evidences,
                                           CapabilityInvocationContext context) {
                    return evaluatorCalls.getAndIncrement() == 0
                            ? Evaluation.insufficient("第一次结果只证明相关事实，没有证明原始目标")
                            : Evaluation.satisfied("第二次计划补足了证明缺口");
                }

                @Override
                public boolean consumesLlmCall() {
                    return false;
                }
            };
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "需要更强证明的目标", 6L, "PATENT", 1L, 2L, "trace-runtime-2", List.of());

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("最终答案", result.answer());
            assertEquals(2, plannerCalls.get(), "INSUFFICIENT 必须重新规划，而不是原样重试 Tool");
            assertEquals(2, evaluatorCalls.get());
            assertEquals(2, result.activities().size());
            assertEquals(2, result.references().size());
            assertTrue(result.traceSteps().stream().anyMatch(s ->
                    "RESULT_EVALUATION".equals(s.phase()) && "REPLAN".equals(s.status())));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void plannerAnswerCannotBypassEvaluatorAndInsufficientStillReplans() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger evaluatorCalls = new AtomicInteger();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new SummaryOnlyCapability(), new DeterministicCapability("strong-fact", "补足后的答案")), List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) -> {
                int call = plannerCalls.getAndIncrement();
                if (call == 0) {
                    return AgentPlanningDecision.execute(new AgentExecutionPlan("weak-plan", state.getOriginalGoal(), replanAttempt,
                            List.of(new PlanNode("weak", "weak-fact", Map.of(), "取得弱事实", Set.of()))));
                }
                if (call == 1) return AgentPlanningDecision.answer();
                return AgentPlanningDecision.execute(new AgentExecutionPlan("strong-plan", state.getOriginalGoal(), replanAttempt,
                        List.of(new PlanNode("strong", "strong-fact", Map.of(), "补足证明缺口", Set.of()))));
            };
            AgentGoalEvaluator evaluator = new AgentGoalEvaluator() {
                @Override
                public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                           List<String> deterministicAnswers,
                                           List<Evidence> evidences,
                                           CapabilityInvocationContext context) {
                    int call = evaluatorCalls.getAndIncrement();
                    return call < 2 ? Evaluation.insufficient("Planner 的 ANSWER 仍未覆盖 OriginalGoal")
                            : Evaluation.satisfied("强事实覆盖 OriginalGoal");
                }

                @Override
                public boolean consumesLlmCall() { return false; }
            };
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "Planner 不能擅自宣布完成的目标", 6L, "PATENT", 1L, 2L, "trace-runtime-3", List.of());

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("补足后的答案", result.answer());
            assertEquals(3, plannerCalls.get());
            assertEquals(3, evaluatorCalls.get());
            assertEquals(2, result.activities().size(), "Planner ANSWER 本身不能伪造一次 Tool Activity");
            assertEquals(2, result.traceSteps().stream()
                    .filter(s -> "RESULT_EVALUATION".equals(s.phase()) && "REPLAN".equals(s.status())).count());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void finalAnswerUsesOnlyEvaluatorProofFrontierAndSkipsStaleEvidencePipeline() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger evaluatorCalls = new AtomicInteger();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(
                        new EvidenceCapability(),
                        new EmptyDeterministicCapability(),
                        new DeterministicCapability("resolved-detail", "最终正确详情：一种代替印花的运动服")),
                List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) -> {
                int call = plannerCalls.getAndIncrement();
                if (call == 0) {
                    return AgentPlanningDecision.execute(new AgentExecutionPlan("discover-plan", state.getOriginalGoal(), replanAttempt,
                            List.of(
                                    new PlanNode("semantic", "candidate-evidence", Map.of(), "定位相关候选", Set.of()),
                                    new PlanNode("wrong-empty", "wrong-empty", Map.of(), "按错误字面条件查询", Set.of())
                            )));
                }
                return AgentPlanningDecision.execute(new AgentExecutionPlan("resolved-plan", state.getOriginalGoal(), replanAttempt,
                        List.of(new PlanNode("detail", "resolved-detail", Map.of(), "查询纠正后的确定性详情", Set.of()))));
            };
            AgentGoalEvaluator evaluator = new AgentGoalEvaluator() {
                @Override
                public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                           List<String> deterministicAnswers, List<Evidence> evidences,
                                           CapabilityInvocationContext context) {
                    if (evaluatorCalls.getAndIncrement() == 0) {
                        return Evaluation.insufficient("候选已定位，但字面结构化查询为空且详情尚未证明");
                    }
                    return Evaluation.satisfied("纠正后的结构化详情单独完整证明目标",
                            List.of("resolved-plan:detail"));
                }

                @Override
                public boolean consumesLlmCall() { return false; }
            };

            // answerPipeline=null 是刻意的：如果历史 semantic Evidence 仍污染最终答案作用域，旧逻辑会直接 STOPPED。
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "帮我检索出来体替代印花的专利详情信息", 6L, "PATENT", 1L, 2L,
                    "trace-proof-frontier", List.of());

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("最终正确详情：一种代替印花的运动服", result.answer());
            assertFalse(result.answer().contains("未找到"));
            assertFalse(result.answer().contains("无关候选证据"));
            assertTrue(result.evidences().isEmpty(), "最终 evidence 只返回 proof frontier 内证据，历史候选证据必须退出答案作用域");
            assertEquals(3, result.references().size(), "完整执行历史仍应保留给 trace/debug");
            assertTrue(result.traceSteps().stream().anyMatch(step ->
                    "ANSWER_VALIDATION".equals(step.phase())
                            && step.summary() != null
                            && step.summary().contains("answerPipelineSkipped=true")));
        } finally {
            invoker.shutdown();
        }
    }

    private AgentGoalEvaluator noCostEvaluator(AgentGoalEvaluator.Evaluation evaluation) {
        return new AgentGoalEvaluator() {
            @Override
            public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                       List<String> deterministicAnswers,
                                       List<Evidence> evidences,
                                       CapabilityInvocationContext context) {
                return evaluation;
            }

            @Override
            public boolean consumesLlmCall() {
                return false;
            }
        };
    }

    private static final class DeterministicCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition;
        private final String answer;

        private DeterministicCapability(String name, String answer) {
            this.definition = new CapabilityDefinition(name, "1", "确定性事实能力", Set.of(), true, 1_000L, 10);
            this.answer = answer;
        }

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return answer; }
                @Override public String progressHash() { return definition.name() + ":" + answer; }
                @Override public String deterministicAnswer() { return answer; }
                @Override public List<Long> verifiedEntityIds() { return List.of(74L); }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1, "completeDataset", true));
        }
    }

    private static final class EmptyDeterministicCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "wrong-empty", "1", "错误字面条件空结果", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "错误字面条件没有命中"; }
                @Override public String progressHash() { return "wrong-empty-v1"; }
                @Override public String deterministicAnswer() { return "未找到符合条件的结果。"; }
            };
            return CapabilityResult.success(output, Map.of(
                    "outputCount", 0,
                    "completeDataset", true,
                    "authoritativeEmpty", true));
        }
    }

    private static final class EvidenceCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "candidate-evidence", "1", "候选语义证据", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            Evidence evidence = Evidence.builder()
                    .chunkId(1L)
                    .documentId("doc-1")
                    .documentName("候选专利")
                    .content("无关候选证据：仅用于定位，不应进入最终回答")
                    .score(0.8D)
                    .build();
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "找到候选证据"; }
                @Override public String progressHash() { return "candidate-evidence-v1"; }
                @Override public List<Evidence> evidences() { return List.of(evidence); }
            };
            return CapabilityResult.success(output, Map.of(
                    "evidenceCount", 1,
                    "completeDataset", false,
                    "outputComplete", false,
                    "limited", true));
        }
    }

    private static final class SummaryOnlyCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "weak-fact", "1", "弱事实能力", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "仅证明相关弱事实"; }
                @Override public String progressHash() { return "weak-v1"; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1, "completeDataset", true));
        }
    }
}
