package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticQueryEngineGoalEvaluatorTest {

    @Test
    void plannerAnswerMustNotBypassIndependentGoalCoverageRejection() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "weak-fact",
                        Map.of("query", "弱事实"), "取得相关但不足的事实", null);
            }
            if (call == 1) {
                return new AgentDecision(AgentActionType.ANSWER, null, Map.of(),
                        "Planner 认为已经足够", null);
            }
            assertTrue(observations.stream().anyMatch(o -> "goal_evaluator".equals(o.capability())
                    && o.recoverableError()
                    && "GOAL_NOT_SATISFIED".equals(o.metadata().get("errorKind"))));
            return new AgentDecision(AgentActionType.STOP, null, Map.of(),
                    "没有能力补足证明缺口", "当前能力不足以完成证明。");
        };

        AgentGoalEvaluator evaluator = (originalGoal, observations, deterministicAnswers, evidences, context) -> {
            assertEquals("目标要求更强事实", originalGoal);
            assertEquals(List.of("仅证明相关弱事实"), deterministicAnswers);
            return AgentGoalEvaluator.Evaluation.insufficient("现有结果只证明相关事实，没有证明原始目标");
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new WeakFactCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(
                    planner, invoker, null, new EvidenceProperties(), evaluator);
            AgenticQueryEngine.Result result = engine.execute(
                    "目标要求更强事实", 6L, "PATENT", 1L, 2L,
                    "trace-goal-evaluator", List.of());

            assertEquals(AgenticQueryEngine.State.STOPPED, result.state());
            assertNull(result.answer());
            assertEquals(3, plannerCalls.get());
            assertTrue(result.traceSteps().stream()
                    .filter(step -> "GOAL_EVALUATOR".equals(step.phase()))
                    .anyMatch(step -> "RETRYABLE".equals(step.status())
                            && step.summary().contains("INSUFFICIENT")
                            && step.summary().contains("originalGoal")));
        } finally {
            invoker.shutdown();
        }
    }

    private static final class WeakFactCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "weak-fact", "1", "测试弱事实能力", Set.of("query"), true, 1_000L, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "只得到相关弱事实"; }
                @Override public String progressHash() { return "weak-fact-v1"; }
                @Override public String deterministicAnswer() { return "仅证明相关弱事实"; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1));
        }
    }
}
