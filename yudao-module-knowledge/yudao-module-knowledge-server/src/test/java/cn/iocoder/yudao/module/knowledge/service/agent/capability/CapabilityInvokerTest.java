package cn.iocoder.yudao.module.knowledge.service.agent.capability;

import cn.iocoder.yudao.module.knowledge.service.agent.AgentStopReason;
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
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new EchoCapability())));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "trace-1");

        CapabilityInvoker.PreparedCall prepared = invoker.prepare(
                "echo", Map.of("query", "专利", "kbId", 999L), context);

        assertFalse(prepared.accepted());
        assertEquals(AgentStopReason.INVALID_CAPABILITY_CALL, prepared.stopReason());
    }

    @Test
    void sameArgumentsAndScopeMustProduceStableFingerprint() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(new EchoCapability())));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "trace-1");

        CapabilityInvoker.PreparedCall first = invoker.prepare(
                "echo", Map.of("query", "专利", "topK", 10), context);
        CapabilityInvoker.PreparedCall second = invoker.prepare(
                "echo", Map.of("topK", 10, "query", "专利"), context);

        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertNotNull(first.fingerprint());
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    private static final class EchoCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "echo", "1", "测试能力", Set.of("query"), true, 1000, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(arguments, Map.of());
        }
    }
}
