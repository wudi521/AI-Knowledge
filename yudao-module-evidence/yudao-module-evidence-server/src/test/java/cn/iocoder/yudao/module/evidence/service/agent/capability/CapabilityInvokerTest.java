package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityInvokerTest {
    @Test
    void plannerMustNotOverrideSystemScope() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new EchoCapability()), List.of()));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-1");
        CapabilityInvoker.PreparedCall prepared = invoker.prepare("echo", Map.of("query", "专利", "kbId", 999L), context);
        assertFalse(prepared.accepted());
        assertFalse(prepared.recoverable());
        assertEquals(AgentStopReason.INVALID_CAPABILITY_CALL, prepared.stopReason());
        invoker.shutdown();
    }

    @Test
    void protectedScopeVariantsMustAlsoBeRejected() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new EchoCapability()), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-scope-variant");
            CapabilityInvoker.PreparedCall prepared = invoker.prepare(
                    "echo", Map.of("query", "专利", "KB_ID", 999L), context);
            assertFalse(prepared.accepted());
            assertFalse(prepared.recoverable());
            assertEquals(AgentStopReason.INVALID_CAPABILITY_CALL, prepared.stopReason());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void declaredArgumentSchemaMustActAsCentralWhitelist() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new StrictCapability()), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-schema");
            CapabilityInvoker.PreparedCall prepared = invoker.prepare(
                    "strict", Map.of("query", "专利", "invented", "candidate-title"), context);
            assertFalse(prepared.accepted());
            assertTrue(prepared.recoverable());
            assertEquals(AgentStopReason.INVALID_CAPABILITY_CALL, prepared.stopReason());
            assertTrue(prepared.message().contains("unknown capability argument"));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void machineValidatorMustRunBeforeCapabilityExecution() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new MachineValidatedCapability()), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-machine-schema");
            CapabilityInvoker.PreparedCall bad = invoker.prepare("machine", Map.of("limit", 0), context);
            CapabilityInvoker.PreparedCall good = invoker.prepare("machine", Map.of("limit", 3), context);

            assertFalse(bad.accepted());
            assertTrue(bad.recoverable());
            assertTrue(bad.message().contains("1..10"));
            assertTrue(good.accepted());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void sameArgumentsAndScopeMustProduceStableFingerprint() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new EchoCapability()), List.of()));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-1");
        CapabilityInvoker.PreparedCall first = invoker.prepare("echo", Map.of("query", "专利", "topK", 10), context);
        CapabilityInvoker.PreparedCall second = invoker.prepare("echo", Map.of("topK", 10, "query", "专利"), context);
        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertNotNull(first.fingerprint());
        assertEquals(first.fingerprint(), second.fingerprint());
        invoker.shutdown();
    }

    @Test
    void capabilityTimeoutMustBeEnforcedByCode() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new SlowCapability()), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-timeout");
            CapabilityInvoker.PreparedCall prepared = invoker.prepare("slow", Map.of("query", "x"), context);
            assertTrue(prepared.accepted());
            CapabilityResult result = invoker.invoke(prepared, context);
            assertFalse(result.success());
            assertEquals(AgentStopReason.TIME_BUDGET_EXCEEDED, result.stopReason());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void capabilityOutputMustRespectMaxRows() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new TooManyRowsCapability()), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-rows");
            CapabilityInvoker.PreparedCall prepared = invoker.prepare("too-many", Map.of("query", "x"), context);
            CapabilityResult result = invoker.invoke(prepared, context);
            assertFalse(result.success());
            assertEquals(AgentStopReason.NO_RELIABLE_EVIDENCE, result.stopReason());
        } finally {
            invoker.shutdown();
        }
    }

    private static final class EchoCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "echo", "1", "测试能力", Set.of("query"), true, 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(arguments, Map.of());
        }
    }

    private static final class StrictCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "strict", "1", "严格参数白名单测试能力",
                Map.of("query", "必填查询文本", "topK", "可选数量"), Set.of("query"), "TEST", true,
                Set.of(), Set.of(), Set.of(), 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(arguments, Map.of());
        }
    }

    private static final class MachineValidatedCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "machine", "1", "机器参数验证测试能力",
                Map.of("limit", "1..10"), Set.of("limit"), "TEST", true,
                Set.of(), Set.of(), Set.of(), 1000, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                                        Map<String, Object> arguments) {
            Object raw = arguments.get("limit");
            if (!(raw instanceof Integer value) || value < 1 || value > 10) {
                return CapabilityArgumentValidation.invalid("limit must be integer 1..10");
            }
            return CapabilityArgumentValidation.ok();
        }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(arguments, Map.of("outputCount", 1));
        }
    }

    private static final class SlowCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "slow", "1", "超时测试能力", Set.of("query"), true, 20, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.success("late", Map.of("outputCount", 1));
        }
    }

    private static final class TooManyRowsCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "too-many", "1", "行数保护测试能力", Set.of("query"), true, 1000, 2);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(List.of("a", "b", "c"), Map.of("outputCount", 3));
        }
    }
}
