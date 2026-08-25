package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityResultStatusTest {

    @Test
    void authoritativeEmptyNormalizesSuccessToEmptyWithoutLosingData() {
        Object data = Map.of("count", 0);
        CapabilityResult result = CapabilityResult.success(data, Map.of(
                "authoritativeEmpty", true,
                "completeDataset", true,
                "outputComplete", true,
                "outputCount", 1));

        assertEquals(CapabilityResultStatus.EMPTY, result.status());
        assertTrue(result.success());
        assertEquals(data, result.data());
    }

    @Test
    void explicitIncompleteOutputNormalizesToPartial() {
        CapabilityResult result = CapabilityResult.success("partial-data", Map.of(
                "outputComplete", false,
                "outputCount", 2));

        assertEquals(CapabilityResultStatus.PARTIAL, result.status());
        assertTrue(result.success());
    }

    @Test
    void retrievalNoMatchesIsEmptyButNotAuthoritativeAbsence() {
        CapabilityResult result = CapabilityResult.success(null, Map.of(
                "retrievalOutcome", "NO_MATCHES",
                "authoritativeEmpty", false,
                "completeDataset", false,
                "outputCount", 0));

        assertEquals(CapabilityResultStatus.EMPTY, result.status());
        assertTrue(result.success());
        assertEquals(false, result.metadata().get("authoritativeEmpty"));
    }

    @Test
    void candidateSearchCanBeSuccessfulWithoutBeingCompleteDataset() {
        CapabilityResult result = CapabilityResult.success("candidates", Map.of(
                "completeDataset", false,
                "outputComplete", true,
                "outputCount", 3));

        assertEquals(CapabilityResultStatus.SUCCESS, result.status());
    }
}
