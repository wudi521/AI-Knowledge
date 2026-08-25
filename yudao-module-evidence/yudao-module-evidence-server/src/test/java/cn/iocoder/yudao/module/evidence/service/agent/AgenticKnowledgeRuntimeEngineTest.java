package cn.iocoder.yudao.module.evidence.service.agent;

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
                                           List<cn.iocoder.yudao.module.evidence.domain.Evidence> evidences,
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

    private AgentGoalEvaluator noCostEvaluator(AgentGoalEvaluator.Evaluation evaluation) {
        return new AgentGoalEvaluator() {
            @Override
            public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                       List<String> deterministicAnswers,
                                       List<cn.iocoder.yudao.module.evidence.domain.Evidence> evidences,
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
