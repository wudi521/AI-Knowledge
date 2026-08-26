package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NoProgressGuardTest {

    @Test
    void planIdsNodeIdsAndPurposeMustNotCreateFakeProgress() {
        Map<String, Object> firstArgs = new LinkedHashMap<>();
        firstArgs.put("select", List.of("TITLE"));
        firstArgs.put("filter", Map.of("field", "TITLE", "operator", "CONTAINS", "values", List.of("磁")));

        AgentExecutionPlan first = new AgentExecutionPlan("plan-1", "goal", 0, List.of(
                new PlanNode("lookup-a", "structured_query", firstArgs, "第一次说明", Set.of()),
                new PlanNode("compare-a", "scalar_compare",
                        Map.of("left", Map.of("$ref", "lookup-a", "selector", "scalarValue"),
                                "operator", "GT", "right", 0),
                        "第一次比较", Set.of("lookup-a"))));

        Map<String, Object> sameArgsDifferentOrder = new LinkedHashMap<>();
        sameArgsDifferentOrder.put("filter", Map.of("values", List.of("磁"), "operator", "CONTAINS", "field", "TITLE"));
        sameArgsDifferentOrder.put("select", List.of("TITLE"));
        AgentExecutionPlan sameSemantics = new AgentExecutionPlan("plan-99", "goal", 1, List.of(
                new PlanNode("x", "structured_query", sameArgsDifferentOrder, "换了文案", Set.of()),
                new PlanNode("y", "scalar_compare",
                        Map.of("right", 0, "operator", "GT",
                                "left", Map.of("selector", "scalarValue", "$ref", "x")),
                        "另一段文案", Set.of("x"))));

        NoProgressGuard guard = new NoProgressGuard();
        guard.markInsufficient(first);

        assertThat(guard.repeatsInsufficient(sameSemantics)).isTrue();
        assertThat(NoProgressGuard.semanticFingerprint(first))
                .isEqualTo(NoProgressGuard.semanticFingerprint(sameSemantics));
    }

    @Test
    void materialArgumentChangeMustCountAsProgress() {
        AgentExecutionPlan first = plan("a", "李");
        AgentExecutionPlan changed = plan("b", "王");
        NoProgressGuard guard = new NoProgressGuard();
        guard.markInsufficient(first);

        assertThat(guard.repeatsInsufficient(changed)).isFalse();
        assertThat(NoProgressGuard.semanticFingerprint(first))
                .isNotEqualTo(NoProgressGuard.semanticFingerprint(changed));
    }

    private AgentExecutionPlan plan(String nodeId, String value) {
        return new AgentExecutionPlan("p-" + nodeId, "goal", 0, List.of(
                new PlanNode(nodeId, "structured_query",
                        Map.of("filter", Map.of("field", "INVENTOR", "operator", "EQ", "values", List.of(value))),
                        "purpose", Set.of())));
    }
}
