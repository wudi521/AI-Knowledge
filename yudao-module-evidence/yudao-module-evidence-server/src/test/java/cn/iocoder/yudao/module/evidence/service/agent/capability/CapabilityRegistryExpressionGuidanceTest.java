package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryExpressionGuidanceTest {

    @Test
    void plannerContractMustExplainDerivedFilterAndElementBinding() {
        KnowledgeCapability capability = new KnowledgeCapability() {
            @Override
            public CapabilityDefinition definition() {
                return new CapabilityDefinition(
                        "typed_rows",
                        "1",
                        "typed row query",
                        Map.of("select", "projection", "filter", "predicate"),
                        Set.of(),
                        "ROWS",
                        true,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        1_000L,
                        20);
            }

            @Override
            public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
                return CapabilityResult.success(null, Map.of());
            }
        };

        CapabilityRegistry registry = new CapabilityRegistry(List.of(capability), List.of());
        CapabilityDefinition planner = registry.listDefinitions(null).get(0);

        assertThat(planner.argumentSchema().get("filter"))
                .contains("transforms/explode")
                .contains("变换后的值")
                .contains("multi-value");
        assertThat(planner.argumentSchema().get("select"))
                .contains("同一 field")
                .contains("explode=true")
                .contains("element binding");
    }
}
