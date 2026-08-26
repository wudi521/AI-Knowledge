package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRuntimeRetryTest {

    @Test
    void transientFailureMustRetryInsideRuntimeAndNotAskPlannerToRepair() {
        TransientThenSuccessCapability capability = new TransientThenSuccessCapability();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(capability), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(
                    1L, 2L, 6L, "PATENT", "trace-transient-retry");
            CapabilityInvoker.PreparedCall call = invoker.prepare("transient-then-success", Map.of("query", "x"), context);

            CapabilityResult result = invoker.invoke(call, context);

            assertTrue(result.success());
            assertEquals(CapabilityResultStatus.SUCCESS, result.status());
            assertEquals(3, capability.calls.get());
            assertFalse(result.recoverable());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void plannerRecoverableValidationMustNeverBeRuntimeRetried() {
        PlannerRepairCapability capability = new PlannerRepairCapability();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(capability), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(
                    1L, 2L, 6L, "PATENT", "trace-planner-repair");
            CapabilityInvoker.PreparedCall call = invoker.prepare("planner-repair", Map.of("query", "x"), context);

            CapabilityResult result = invoker.invoke(call, context);

            assertFalse(result.success());
            assertEquals(CapabilityResultStatus.FAILED, result.status());
            assertEquals(CapabilityFailureType.VALIDATION, result.failureType());
            assertTrue(result.recoverable());
            assertFalse(result.runtimeRetryable());
            assertEquals(1, capability.calls.get());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void dataIncompleteMustNotBeRuntimeRetried() {
        DataIncompleteCapability capability = new DataIncompleteCapability();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(capability), List.of()));
        try {
            CapabilityInvocationContext context = new CapabilityInvocationContext(
                    1L, 2L, 6L, "PATENT", "trace-data-incomplete");
            CapabilityInvoker.PreparedCall call = invoker.prepare("data-incomplete", Map.of("query", "x"), context);

            CapabilityResult result = invoker.invoke(call, context);

            assertFalse(result.success());
            assertEquals(CapabilityFailureType.DATA_INCOMPLETE, result.failureType());
            assertFalse(result.runtimeRetryable());
            assertEquals(1, capability.calls.get());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void invocationTenantMustBeBoundInsideRuntimeWorker() {
        TenantAwareCapability capability = new TenantAwareCapability();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(capability), List.of()));
        Long oldTenantId = TenantContextHolder.getTenantId();
        boolean oldIgnore = TenantContextHolder.isIgnore();
        try {
            // 模拟提交任务时调用线程携带了另一个租户。worker 必须以服务端注入的 invocation context 为准。
            TenantContextHolder.setTenantId(999L);
            TenantContextHolder.setIgnore(true);
            CapabilityInvocationContext context = new CapabilityInvocationContext(
                    1L, 2L, 6L, "PATENT", "trace-tenant-propagation");
            CapabilityInvoker.PreparedCall call = invoker.prepare("tenant-aware", Map.of("query", "x"), context);

            CapabilityResult result = invoker.invoke(call, context);

            assertTrue(result.success());
            assertEquals(1L, capability.observedTenantId.get());
            assertFalse(capability.observedIgnore.get());
            // worker 的上下文切换不能反向污染提交任务的调用线程。
            assertEquals(999L, TenantContextHolder.getTenantId());
            assertTrue(TenantContextHolder.isIgnore());
        } finally {
            invoker.shutdown();
            TenantContextHolder.clear();
            if (oldTenantId != null) TenantContextHolder.setTenantId(oldTenantId);
            if (oldIgnore) TenantContextHolder.setIgnore(true);
        }
    }

    @Test
    void onlyThreeRuntimeFailureTypesAreRetryable() {
        assertTrue(CapabilityFailureType.TIMEOUT.retryable());
        assertTrue(CapabilityFailureType.THROTTLED.retryable());
        assertTrue(CapabilityFailureType.TRANSIENT.retryable());

        assertFalse(CapabilityFailureType.VALIDATION.retryable());
        assertFalse(CapabilityFailureType.PERMISSION.retryable());
        assertFalse(CapabilityFailureType.CONFIGURATION.retryable());
        assertFalse(CapabilityFailureType.DEPENDENCY.retryable());
        assertFalse(CapabilityFailureType.DATA_INCOMPLETE.retryable());
    }

    private static final class TransientThenSuccessCapability implements KnowledgeCapability {
        private final AtomicInteger calls = new AtomicInteger();
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "transient-then-success", "1", "transient retry test", Set.of("query"), true, 1_000L, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                return CapabilityResult.failure(CapabilityFailureType.TRANSIENT,
                        AgentStopReason.NO_RELIABLE_EVIDENCE, "temporary dependency failure");
            }
            return CapabilityResult.success("ok", Map.of("outputCount", 1));
        }
    }

    private static final class PlannerRepairCapability implements KnowledgeCapability {
        private final AtomicInteger calls = new AtomicInteger();
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "planner-repair", "1", "planner repair test", Set.of("query"), true, 1_000L, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            calls.incrementAndGet();
            return CapabilityResult.recoverableFailure("query combination is invalid",
                    Map.of("errorKind", "PLAN_CONTRACT"));
        }
    }

    private static final class DataIncompleteCapability implements KnowledgeCapability {
        private final AtomicInteger calls = new AtomicInteger();
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "data-incomplete", "1", "data incomplete test", Set.of("query"), true, 1_000L, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            calls.incrementAndGet();
            return CapabilityResult.failure(CapabilityFailureType.DATA_INCOMPLETE,
                    AgentStopReason.NO_RELIABLE_EVIDENCE, "required source data is incomplete");
        }
    }

    private static final class TenantAwareCapability implements KnowledgeCapability {
        private final AtomicReference<Long> observedTenantId = new AtomicReference<>();
        private final AtomicBoolean observedIgnore = new AtomicBoolean(true);
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "tenant-aware", "1", "tenant propagation test", Set.of("query"), true, 1_000L, 10);

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            observedTenantId.set(TenantContextHolder.getTenantId());
            observedIgnore.set(TenantContextHolder.isIgnore());
            return CapabilityResult.success("ok", Map.of("outputCount", 1));
        }
    }
}
