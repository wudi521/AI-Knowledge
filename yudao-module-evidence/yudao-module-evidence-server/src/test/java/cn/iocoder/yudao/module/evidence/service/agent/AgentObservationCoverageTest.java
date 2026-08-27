package cn.iocoder.yudao.module.evidence.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentObservationCoverageTest {

    @Test
    void finalLimitMustNotDowngradeFullSourceCoverage() {
        AgentObservation observation = AgentObservation.success(
                "structured_query",
                "查询全库标题长度降序第一名",
                "top1",
                "ref-1",
                Map.of(
                        "completeDataset", true,
                        "outputComplete", false,
                        "limited", true,
                        "outputCount", 1,
                        "fullOutputCount", 9,
                        "sourceRowCount", 9,
                        "missingValueCount", 0));

        assertTrue(observation.completeDataset());
        assertEquals(AgentObservation.COVERAGE_COMPLETE,
                observation.metadata().get(AgentObservation.META_REQUIRED_COVERAGE));
        assertTrue(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_COVERAGE_COMPLETE)));
        assertFalse(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_SOURCE_TRUNCATED)));
        assertTrue(Boolean.TRUE.equals(observation.metadata().get("outputLimited")));
        assertEquals(9, ((Number) observation.metadata().get("rowsConsidered")).intValue());
        assertEquals(9, ((Number) observation.metadata().get("matchedRowCount")).intValue());
        assertEquals(1, ((Number) observation.metadata().get("resultLimit")).intValue());
    }

    @Test
    void sourceTruncationMustMakeGlobalCoverageIncomplete() {
        AgentObservation observation = AgentObservation.success(
                "structured_query",
                "查询全库最大值",
                "partial-source",
                "ref-2",
                Map.of(
                        "completeDataset", true,
                        "sourceTruncated", true,
                        "outputComplete", true,
                        "sourceRowCount", 2000,
                        "missingValueCount", 0));

        assertEquals(AgentObservation.COVERAGE_COMPLETE,
                observation.metadata().get(AgentObservation.META_REQUIRED_COVERAGE));
        assertFalse(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_COVERAGE_COMPLETE)));
        assertTrue(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_SOURCE_TRUNCATED)));
        assertFalse(Boolean.TRUE.equals(observation.metadata().get("outputLimited")));
    }

    @Test
    void missingRequiredValuesMustMakeCoverageIncomplete() {
        AgentObservation observation = AgentObservation.success(
                "structured_query",
                "计算全库平均值",
                "missing-values",
                "ref-3",
                Map.of(
                        "completeDataset", true,
                        "outputComplete", true,
                        "sourceRowCount", 9,
                        "missingValueCount", 1));

        assertEquals(AgentObservation.COVERAGE_COMPLETE,
                observation.metadata().get(AgentObservation.META_REQUIRED_COVERAGE));
        assertFalse(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_COVERAGE_COMPLETE)));
    }

    @Test
    void structuredCallerCannotDowngradeCoverageRequirement() {
        AgentObservation observation = AgentObservation.success(
                "structured_query",
                "任意结构化查询",
                "attempt-downgrade",
                "ref-4",
                Map.of(
                        AgentObservation.META_REQUIRED_COVERAGE, "BOUNDED",
                        "completeDataset", true,
                        "sourceTruncated", false,
                        "missingValueCount", 0));

        assertEquals(AgentObservation.COVERAGE_COMPLETE,
                observation.metadata().get(AgentObservation.META_REQUIRED_COVERAGE));
        assertEquals(1, ((Number) observation.metadata().get("coverageContractVersion")).intValue());
        assertTrue(Boolean.TRUE.equals(observation.metadata().get(AgentObservation.META_COVERAGE_COMPLETE)));
    }

    @Test
    void nonStructuredObservationMustNotInventCompleteDatasetContract() {
        AgentObservation observation = AgentObservation.success(
                "knowledge_retrieval",
                "召回局部证据",
                "retrieved",
                "ref-5",
                Map.of("outputCount", 5));

        assertFalse(observation.metadata().containsKey(AgentObservation.META_REQUIRED_COVERAGE));
        assertFalse(observation.metadata().containsKey(AgentObservation.META_COVERAGE_COMPLETE));
    }
}
