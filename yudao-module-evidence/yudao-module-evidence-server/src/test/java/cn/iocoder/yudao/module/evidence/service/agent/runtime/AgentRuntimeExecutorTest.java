package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityFailureType;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeExecutorTest {

    @Test
    void executesDagAndResolvesTypedNodeReference() {
        AtomicReference<Object> consumedIds = new AtomicReference<>();
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new IdSourceCapability(), new EchoCapability(), new IdConsumerCapability(consumedIds)), List.of()));
        try {
            AgentRuntimeExecutor runtime = new AgentRuntimeExecutor(invoker);
            AgentExecutionPlan plan = new AgentExecutionPlan("plan-1", "组合多个事实完成目标", 0, List.of(
                    new PlanNode("ids", "id-source", Map.of(), "取得可信实体集合", Set.of()),
                    new PlanNode("independent", "echo", Map.of("query", "并行事实"), "取得独立事实", Set.of()),
                    new PlanNode("consume", "id-consumer",
                            Map.of("ids", Map.of("$ref", "ids", "selector", "verifiedEntityIds")),
                            "消费前序结构化结果", Set.of("ids"))
            ));

            AgentRuntimeResult result = runtime.execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-dag"),
                    new AgentExecutionBudget(6, 6, 5_000L));

            assertEquals(CapabilityResultStatus.SUCCESS, result.status());
            assertEquals(3, result.nodeResults().size());
            assertEquals(3, result.activities().size());
            assertEquals(3, result.references().size());
            assertEquals(3, result.provenance().size());
            assertEquals(List.of(11L, 12L), consumedIds.get());
            assertTrue(result.provenance().stream().allMatch(p -> "trace-dag".equals(p.traceId())));
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void runtimeBudgetCapsCapabilityTimeoutAndPreventsRetryPastDeadline() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new SlowCapability()), List.of()));
        try {
            AgentRuntimeExecutor runtime = new AgentRuntimeExecutor(invoker);
            AgentExecutionPlan plan = new AgentExecutionPlan("budget-plan", "在请求预算内执行", 0, List.of(
                    new PlanNode("slow", "slow", Map.of(), "慢调用", Set.of())
            ));

            long started = System.currentTimeMillis();
            AgentRuntimeResult result = runtime.execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-budget"),
                    new AgentExecutionBudget(2, 2, 80L));
            long elapsed = System.currentTimeMillis() - started;

            CapabilityResult node = result.nodeResults().get("slow");
            assertEquals(CapabilityResultStatus.FAILED, result.status());
            assertEquals(CapabilityFailureType.TIMEOUT, node.failureType());
            assertEquals(Boolean.TRUE, node.metadata().get("runtimeBudgetExhausted"));
            assertTrue(((Number) node.metadata().get("appliedTimeoutMs")).longValue() <= 80L);
            assertTrue(elapsed < 400L, "runtime budget must cap the 1000ms capability timeout and its retries");
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void validatorRejectsCycleAndUndeclaredDataDependency() {
        AgentExecutionPlanValidator validator = new AgentExecutionPlanValidator();
        AgentExecutionBudget budget = new AgentExecutionBudget(6, 6, 5_000L);

        AgentExecutionPlan cycle = new AgentExecutionPlan("cycle", "x", 0, List.of(
                new PlanNode("a", "echo", Map.of(), "a", Set.of("b")),
                new PlanNode("b", "echo", Map.of(), "b", Set.of("a"))
        ));
        assertFalse(validator.validate(cycle, budget).valid());

        AgentExecutionPlan hiddenReference = new AgentExecutionPlan("hidden-ref", "x", 0, List.of(
                new PlanNode("a", "echo", Map.of(), "a", Set.of()),
                new PlanNode("b", "echo", Map.of("ids", Map.of("$ref", "a", "selector", "data")),
                        "b", Set.of())
        ));
        AgentExecutionPlanValidator.Validation validation = validator.validate(hiddenReference, budget);
        assertFalse(validation.valid());
        assertTrue(validation.message().contains("dependsOn"));
    }

    private static final class IdSourceCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "id-source", "1", "产生可信实体集合", Set.of(), true, 1_000L, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "verified ids"; }
                @Override public String progressHash() { return "ids-11-12"; }
                @Override public List<Long> verifiedEntityIds() { return List.of(11L, 12L); }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 2));
        }
    }

    private static final class EchoCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "echo", "1", "独立事实", Set.of("query"), true, 1_000L, 10);
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return String.valueOf(arguments.get("query")); }
                @Override public String progressHash() { return "echo-" + arguments.get("query"); }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1));
        }
    }

    private static final class IdConsumerCapability implements KnowledgeCapability {
        private final AtomicReference<Object> consumedIds;
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "id-consumer", "1", "消费实体集合", Set.of("ids"), true, 1_000L, 10);
        private IdConsumerCapability(AtomicReference<Object> consumedIds) { this.consumedIds = consumedIds; }
        @Override public CapabilityDefinition definition() { return definition; }
        @Override public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            consumedIds.set(arguments.get("ids"));
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "consumed"; }
                @Override public String progressHash() { return "consumed"; }
                @Override public String deterministicAnswer() { return "已消费前序实体集合"; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1));
        }
    }

    private static final class SlowCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "slow", "1", "慢调用", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.success(Map.of("done", true), Map.of("outputCount", 1));
        }
    }
}
