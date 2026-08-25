package cn.iocoder.yudao.module.evidence.service.agent;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticQueryEngineSelfRepairTest {

    @Test
    void recoverableContractErrorReturnsObservationAndAllowsOneCorrectedCall() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "repairable",
                        Map.of("mode", "bad"), "第一次尝试", null);
            }
            if (call == 1) {
                assertEquals(1, observations.size());
                assertTrue(observations.get(0).recoverableError());
                assertEquals("ERROR", observations.get(0).status());
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "repairable",
                        Map.of("mode", "good"), "根据契约错误修正参数", null);
            }
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "证据充分", null);
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new RepairableCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "执行一个需要自修复的查询", 6L, "PATENT", 1L, 2L, "trace-repair", List.of());

            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals("修复后结果", result.answer());
            assertTrue(result.traceSteps().stream().anyMatch(step -> "RETRYABLE".equals(step.status())
                    && step.argumentsSummary() != null && step.argumentsSummary().contains("bad")));
            assertTrue(result.traceSteps().stream().anyMatch(step -> "CAPABILITY".equals(step.phase())
                    && "SUCCEEDED".equals(step.status())
                    && step.argumentsSummary() != null && step.argumentsSummary().contains("good")));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void nonRecoverableFailureStopsImmediately() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            plannerCalls.incrementAndGet();
            return new AgentDecision(AgentActionType.CALL_CAPABILITY, "fatal",
                    Map.of("query", "x"), "执行不可修复能力", null);
        };
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new FatalCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "不可修复错误", 6L, "PATENT", 1L, 2L, "trace-fatal", List.of());
            assertEquals(AgenticQueryEngine.State.STOPPED, result.state());
            assertEquals(1, plannerCalls.get());
            assertEquals(AgentStopReason.PERMISSION_DENIED, result.stopReason());
        } finally {
            invoker.shutdown();
        }
    }

    private static final class RepairableCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "repairable", "1", "自修复测试能力", Set.of("mode"), true, 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            if (!"good".equals(arguments.get("mode"))) {
                return CapabilityResult.recoverableFailure("mode must be good", Map.of("allowed", List.of("good")));
            }
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "ok"; }
                @Override public String progressHash() { return "ok"; }
                @Override public String deterministicAnswer() { return "修复后结果"; }
            };
            return CapabilityResult.success(output, Map.of("completeDataset", true, "authoritativeEmpty", false));
        }
    }

    private static final class FatalCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "fatal", "1", "不可修复测试能力", Set.of("query"), true, 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "denied");
        }
    }
}
