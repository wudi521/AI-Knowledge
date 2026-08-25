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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void prepareContractErrorAlsoReturnsObservationAndCanBeCorrected() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                // invented 不在 capability 参数白名单内，应在 prepare 阶段被拒绝但允许自修复。
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "strict-repair",
                        Map.of("invented", "x"), "第一次使用了错误参数名", null);
            }
            if (call == 1) {
                assertEquals(1, observations.size());
                AgentObservation observation = observations.get(0);
                assertTrue(observation.recoverableError());
                assertEquals("PREPARE_CONTRACT", observation.metadata().get("errorKind"));
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "strict-repair",
                        Map.of("mode", "good"), "按 capability schema 修正参数", null);
            }
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "结果已足够", null);
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new StrictRepairCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "prepare 阶段也应允许有限自修复", 6L, "PATENT", 1L, 2L,
                    "trace-prepare-repair", List.of());

            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals("prepare 修复后结果", result.answer());
            assertTrue(result.traceSteps().stream().anyMatch(step -> "CAPABILITY_PREPARE".equals(step.phase())
                    && "RETRYABLE".equals(step.status())
                    && step.argumentsSummary() != null && step.argumentsSummary().contains("invented")));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void protectedScopeViolationMustNeverBecomeRecoverable() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            plannerCalls.incrementAndGet();
            return new AgentDecision(AgentActionType.CALL_CAPABILITY, "strict-repair",
                    Map.of("mode", "good", "kbId", 999L), "尝试覆盖系统知识库范围", null);
        };
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new StrictRepairCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "越权参数不能重试", 6L, "PATENT", 1L, 2L, "trace-scope-deny", List.of());
            assertEquals(AgenticQueryEngine.State.STOPPED, result.state());
            assertEquals(AgentStopReason.INVALID_CAPABILITY_CALL, result.stopReason());
            assertEquals(1, plannerCalls.get());
            assertFalse(result.traceSteps().stream().anyMatch(step -> "RETRYABLE".equals(step.status())));
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

    private static final class StrictRepairCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "strict-repair", "1", "prepare 自修复测试能力",
                Map.of("mode", "必填模式"), Set.of("mode"), "TEST", true,
                Set.of(), Set.of(), Set.of(), 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "ok"; }
                @Override public String progressHash() { return "prepare-ok"; }
                @Override public String deterministicAnswer() { return "prepare 修复后结果"; }
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
