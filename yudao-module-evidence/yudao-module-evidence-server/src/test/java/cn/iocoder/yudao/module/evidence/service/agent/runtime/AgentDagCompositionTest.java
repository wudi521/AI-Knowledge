package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.EntitySetOperationCapability;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the generic A + B -> intersection C DAG composition used by cross-channel queries. */
class AgentDagCompositionTest {

    @Test
    void parallelFactsCanFeedGenericIntersectionNode() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(
                new FixedEntitySetCapability("structured-filter", List.of(1L, 2L, 3L)),
                new FixedEntitySetCapability("semantic-filter", List.of(2L, 3L, 4L)),
                new EntitySetOperationCapability()), List.of()));
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan("compose-1", "组合结构化与语义条件", 0, List.of(
                    new PlanNode("a", "structured-filter", Map.of(), "结构化条件集合", Set.of()),
                    new PlanNode("b", "semantic-filter", Map.of(), "语义条件候选集合", Set.of()),
                    new PlanNode("c", EntitySetOperationCapability.NAME,
                            Map.of(
                                    "operation", "INTERSECT",
                                    "sets", List.of(
                                            Map.of("$ref", "a", "selector", "verifiedEntityIds"),
                                            Map.of("$ref", "b", "selector", "verifiedEntityIds")
                                    )),
                            "取两个集合交集", Set.of("a", "b"))
            ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-compose"),
                    new AgentExecutionBudget(6, 6, 5_000L));

            EntitySetOperationCapability.Output output = (EntitySetOperationCapability.Output) result.nodeResults()
                    .get("c").data();
            assertEquals(List.of(2L, 3L), output.verifiedEntityIds());
            assertEquals(List.of(2L, 3L), result.references().stream()
                    .filter(r -> "c".equals(r.nodeId())).findFirst().orElseThrow().verifiedEntityIds());
            assertEquals(3, result.activities().size());
        } finally {
            invoker.shutdown();
        }
    }

    private static final class FixedEntitySetCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition;
        private final List<Long> ids;

        private FixedEntitySetCapability(String name, List<Long> ids) {
            this.definition = new CapabilityDefinition(name, "1", "测试实体集合来源", Set.of(), true, 1_000L, 20);
            this.ids = ids;
        }

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return definition.name() + ids; }
                @Override public String progressHash() { return definition.name() + ids; }
                @Override public List<Long> verifiedEntityIds() { return ids; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", ids.size(), "outputComplete", true));
        }
    }
}
