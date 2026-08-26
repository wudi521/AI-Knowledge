package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExactTextSearchCapabilityTest {

    @Test
    void exactTextUsesTrustedContextAndExplicitDomainCandidateMapping() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence evidence = Evidence.builder()
                .chunkId(101L).documentId("74").documentName("测试专利")
                .content("这里逐字包含磁涌技术").score(1D).products(List.of()).channels(List.of("bm25"))
                .build();
        when(retriever.exactText("磁涌", List.of(6L), List.of(74L), 20,
                1L, 2L, "PATENT", "ag-exact")).thenReturn(new PlannedEvidenceRetriever.Result(
                List.of(evidence), null, null, 1L, true));

        DomainEvidenceEntityMapper mapper = new DomainEvidenceEntityMapper() {
            @Override public String domainCode() { return "PATENT"; }
            @Override public Long candidateEntityId(Evidence source) {
                return source == null || source.getDocumentId() == null ? null : Long.valueOf(source.getDocumentId());
            }
        };
        ExactTextSearchCapability capability = new ExactTextSearchCapability(
                retriever, new DomainEvidenceEntityMapperRegistry(List.of(mapper)));
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "ag-exact", java.util.Set.of(), java.util.Set.of(),
                List.of(74L), "test", false);
        CapabilityResult result = capability.execute(context,
                Map.of("text", "磁涌", "scope", "CONTEXT"));

        assertTrue(result.success());
        ExactTextSearchCapability.Output output = (ExactTextSearchCapability.Output) result.data();
        assertEquals(1, output.evidences().size());
        assertEquals("这里逐字包含磁涌技术", output.evidences().get(0).getContent());
        assertEquals(List.of(74L), output.candidateEntityIds());
        assertTrue(output.verifiedEntityIds().isEmpty(), "原文命中不是新的 trusted entity 来源");
        assertEquals(true, result.metadata().get("candidateEntityMapped"));
        assertEquals(false, result.metadata().get("scopeBlocked"));
        verify(retriever).exactText("磁涌", List.of(6L), List.of(74L), 20,
                1L, 2L, "PATENT", "ag-exact");
    }

    @Test
    void domainWithoutMapperDoesNotGuessExactTextEntityId() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence evidence = Evidence.builder().chunkId(102L).documentId("74").content("逐字命中").build();
        when(retriever.exactText("x", List.of(6L), null, 20,
                1L, 2L, "CONTRACT", "ag-exact-no-map")).thenReturn(new PlannedEvidenceRetriever.Result(
                List.of(evidence), null, null, 1L, true));

        ExactTextSearchCapability capability = new ExactTextSearchCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "CONTRACT", "ag-exact-no-map"),
                Map.of("text", "x"));
        ExactTextSearchCapability.Output output = (ExactTextSearchCapability.Output) result.data();

        assertTrue(output.candidateEntityIds().isEmpty());
        assertTrue(output.verifiedEntityIds().isEmpty());
        assertEquals(false, result.metadata().get("candidateEntityMapped"));
    }

    @Test
    void scopeBlockedIsExplicitAndNeverAuthoritativeEmpty() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        when(retriever.exactText("磁涌", List.of(6L), null, 20,
                1L, 2L, "PATENT", "ag-exact-blocked")).thenReturn(new PlannedEvidenceRetriever.Result(
                PlannedEvidenceRetriever.Status.BLOCKED, "exact identifier resolved to no document",
                List.of(), null, null, null, null, null));

        ExactTextSearchCapability capability = new ExactTextSearchCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-exact-blocked"),
                Map.of("text", "磁涌"));

        assertTrue(result.success());
        assertEquals("BLOCKED", result.metadata().get("retrievalOutcome"));
        assertEquals(true, result.metadata().get("scopeBlocked"));
        assertEquals("exact identifier resolved to no document", result.metadata().get("blockReason"));
        assertEquals(false, result.metadata().get("authoritativeEmpty"));
        assertEquals(false, result.metadata().get("totalHitsExact"));
    }

    @Test
    void contextScopeWithoutTrustedEntityMustAskForMoreInfo() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        ExactTextSearchCapability capability = new ExactTextSearchCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-exact-empty"),
                Map.of("text", "磁涌", "scope", "CONTEXT"));

        assertFalse(result.success());
        assertEquals(AgentStopReason.NEED_USER_INPUT, result.stopReason());
    }
}
