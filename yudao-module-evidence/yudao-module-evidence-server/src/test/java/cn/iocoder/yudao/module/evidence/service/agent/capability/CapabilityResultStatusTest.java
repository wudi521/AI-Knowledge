package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void finalLimitOnCompleteComputationMustRemainSuccess() {
        CapabilityResult result = CapabilityResult.success("top1", Map.of(
                "completeDataset", true,
                "coverageComplete", true,
                "sourceTruncated", false,
                "outputComplete", false,
                "limited", true,
                "outputCount", 1,
                "fullOutputCount", 9));

        assertEquals(CapabilityResultStatus.SUCCESS, result.status());
        assertTrue(result.success());
        assertTrue(Boolean.TRUE.equals(result.metadata().get("outputLimited")));
        assertFalse(result.partial());
    }

    @Test
    void sourceTruncationStillNormalizesToPartial() {
        CapabilityResult result = CapabilityResult.success("unsafe-top1", Map.of(
                "completeDataset", false,
                "coverageComplete", false,
                "sourceTruncated", true,
                "outputComplete", false,
                "limited", true,
                "outputCount", 1));

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
