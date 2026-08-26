package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalarCompareCapabilityTest {

    @Test
    void comparesScalarValuesFromStructuredMetadata() {
        ScalarCompareCapability capability = new ScalarCompareCapability();

        CapabilityResult result = capability.execute(null, Map.of(
                "left", Map.of("resultShape", "SCALAR", "scalarValue", 10D, "dataGrain", "SOURCE_RECORD"),
                "operator", "GT",
                "right", Map.of("resultShape", "SCALAR", "scalarValue", 9D, "dataGrain", "LOGICAL_ENTITY")));

        assertTrue(result.success());
        ScalarCompareCapability.Output output = (ScalarCompareCapability.Output) result.data();
        assertTrue(output.matched());
        assertEquals("BOOLEAN_SCALAR", result.metadata().get("resultShape"));
        assertEquals(true, result.metadata().get("booleanValue"));
    }

    @Test
    void rejectsEntitySetShapeInsteadOfGuessing() {
        ScalarCompareCapability capability = new ScalarCompareCapability();

        CapabilityArgumentValidation validation = capability.validateArguments(null, Map.of(
                "left", Map.of("candidateEntityIds", java.util.List.of(1L, 2L)),
                "operator", "GT",
                "right", 1));

        assertEquals(false, validation.valid());
    }
}
