package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanArgumentResolverCompletenessTest {

    @Test
    void incompleteDataflowRowsAreRejectedByDefault() {
        CapabilityResult upstream = partialRows();
        Map<String, Object> ref = Map.of(
                "$ref", "n1",
                "selector", "metadata",
                "path", "dataflowRows[*].groupKey",
                "required", true,
                "expect", "LIST"
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PlanArgumentResolver().resolve(Map.of("values", ref), Map.of("n1", upstream)));

        assertTrue(error.getMessage().contains("dataflow output is incomplete"));
        assertTrue(error.getMessage().contains("allowPartial=true"));
    }

    @Test
    void partialDataflowRowsRequireExplicitOptIn() {
        CapabilityResult upstream = partialRows();
        Map<String, Object> ref = Map.of(
                "$ref", "n1",
                "selector", "metadata",
                "path", "dataflowRows[*].groupKey",
                "distinct", true,
                "required", true,
                "expect", "LIST",
                "allowPartial", true
        );

        Map<String, Object> resolved = new PlanArgumentResolver().resolve(
                Map.of("values", ref), Map.of("n1", upstream));

        assertEquals(List.of("P-1", "P-2"), resolved.get("values"));
    }

    @Test
    void allowPartialReferenceFlagMustBeBoolean() {
        String validation = PlanArgumentResolver.validateReference(Map.of(
                "$ref", "n1",
                "selector", "metadata",
                "path", "dataflowRows[*].groupKey",
                "allowPartial", "yes"
        ));

        assertEquals("plan reference allowPartial must be boolean", validation);
    }

    private CapabilityResult partialRows() {
        return CapabilityResult.success(null, Map.of(
                StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY, List.of(
                        Map.of("groupKey", "P-1"),
                        Map.of("groupKey", "P-2")
                ),
                "outputComplete", false,
                "limited", true
        ));
    }
}
