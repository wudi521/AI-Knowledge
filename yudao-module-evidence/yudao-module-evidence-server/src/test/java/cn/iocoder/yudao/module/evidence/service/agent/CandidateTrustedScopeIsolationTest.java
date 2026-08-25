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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Candidate entity ids may participate in DAG reasoning but must never contaminate trusted scope. */
class CandidateTrustedScopeIsolationTest {

    @Test
    void candidateIdsNeverEnterTrustedScopeAcrossReplan() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger evaluatorCalls = new AtomicInteger();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(
                new CandidateOnlyCapability(), new ScopeCheckingVerifiedCapability()), List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) -> {
                int call = plannerCalls.getAndIncrement();
                if (call == 0) {
                    return AgentPlanningDecision.execute(new AgentExecutionPlan(
                            "candidate-plan", state.getOriginalGoal(), replanAttempt,
                            List.of(new PlanNode("candidate", "candidate-only", Map.of(),
                                    "取得语义候选实体", Set.of()))));
                }
                return AgentPlanningDecision.execute(new AgentExecutionPlan(
                        "verified-plan", state.getOriginalGoal(), replanAttempt,
                        List.of(new PlanNode("verified", "scope-check", Map.of(),
                                "用确定性事实补足目标", Set.of()))));
            };
            AgentGoalEvaluator evaluator = new AgentGoalEvaluator() {
                @Override
                public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                           List<String> deterministicAnswers,
                                           List<cn.iocoder.yudao.module.evidence.domain.Evidence> evidences,
                                           CapabilityInvocationContext context) {
                    return evaluatorCalls.getAndIncrement() == 0
                            ? Evaluation.insufficient("候选实体只能缩小搜索范围，尚未形成确定性事实")
                            : Evaluation.satisfied("确定性 Tool 已补足原始目标");
                }

                @Override
                public boolean consumesLlmCall() {
                    return false;
                }
            };
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "验证候选实体不会污染可信范围", 6L, "PATENT", 1L, 2L,
                    "trace-candidate-scope", List.of(), List.of(7L));

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("可信事实已确认", result.answer());
            assertEquals(2, plannerCalls.get());
            assertEquals(2, evaluatorCalls.get());
            assertTrue(result.references().stream()
                    .filter(r -> "candidate".equals(r.nodeId()))
                    .anyMatch(r -> r.candidateEntityIds().equals(List.of(99L))
                            && r.verifiedEntityIds().isEmpty()));
            assertTrue(result.verifiedEntityIds().contains(7L), "initial trusted id must remain trusted");
            assertTrue(result.verifiedEntityIds().contains(8L), "deterministically verified id must join trusted scope");
            assertFalse(result.verifiedEntityIds().contains(99L), "candidate id must never enter trusted scope");
        } finally {
            invoker.shutdown();
        }
    }

    private static final class CandidateOnlyCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "candidate-only", "1", "语义候选测试能力", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            assertEquals(List.of(7L), context.contextEntityIds());
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "候选实体 99"; }
                @Override public String progressHash() { return "candidate-99"; }
                @Override public List<Long> candidateEntityIds() { return List.of(99L); }
            };
            return CapabilityResult.success(output, Map.of(
                    "outputCount", 1,
                    "completeDataset", false,
                    "outputComplete", true));
        }
    }

    private static final class ScopeCheckingVerifiedCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "scope-check", "1", "可信范围检查测试能力", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            assertEquals(List.of(7L), context.contextEntityIds(),
                    "candidate id 99 must not be injected into Runtime trusted context");
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "可信实体 8"; }
                @Override public String progressHash() { return "verified-8"; }
                @Override public List<Long> verifiedEntityIds() { return List.of(8L); }
                @Override public String deterministicAnswer() { return "可信事实已确认"; }
            };
            return CapabilityResult.success(output, Map.of(
                    "outputCount", 1,
                    "completeDataset", true,
                    "outputComplete", true));
        }
    }
}
