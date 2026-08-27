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

class KnowledgeRetrievalCapabilityTest {

    @Test
    void semanticCandidatesUseExplicitDomainMappingButNeverBecomeTrusted() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence candidate = Evidence.builder()
                .chunkId(201L).documentId("74").documentName("倾转小翼垂直起降固定翼无人机")
                .content("一种无人机技术方案").score(1D).products(List.of()).channels(List.of("vector"))
                .build();
        when(retriever.search("垂直起降无人机技术", List.of("无人机垂直起降技术"),
                List.of(6L), null, 8, 1L, 2L, "PATENT", "ag-retrieval"))
                .thenReturn(new PlannedEvidenceRetriever.Result(List.of(candidate), null, null, null, null));

        DomainEvidenceEntityMapper mapper = numericDocumentMapper("PATENT");
        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(
                retriever, new DomainEvidenceEntityMapperRegistry(List.of(mapper)));
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-retrieval"),
                Map.of("query", "垂直起降无人机技术", "variants", List.of("无人机垂直起降技术"), "topK", 8));

        assertTrue(result.success());
        KnowledgeRetrievalCapability.Output output = (KnowledgeRetrievalCapability.Output) result.data();
        assertEquals(1, output.evidences().size());
        assertEquals(List.of(74L), output.candidateEntityIds());
        assertTrue(output.verifiedEntityIds().isEmpty(), "普通语义候选绝不能升级为 trusted entity");
        assertEquals(true, result.metadata().get("candidateEntityMapped"));
        verify(retriever).search("垂直起降无人机技术", List.of("无人机垂直起降技术"),
                List.of(6L), null, 8, 1L, 2L, "PATENT", "ag-retrieval");
        capability.shutdown();
    }

    @Test
    void candidateTopNLimitsEntityBreadthWithoutDiscardingEvidenceDepth() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        List<Evidence> ranked = List.of(
                evidence(1L, "66", "一种代替印花的运动服", 1.00D),
                evidence(2L, "77", "一种体外经颅式治疗仪", 0.80D),
                evidence(3L, "88", "一种多功能药物载体的制备方法", 0.60D)
        );
        when(retriever.search("体替代印花", List.of(), List.of(6L), null, 5,
                1L, 2L, "PATENT", "ag-candidate-width"))
                .thenReturn(new PlannedEvidenceRetriever.Result(ranked, null, null, null, null));

        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(
                retriever, new DomainEvidenceEntityMapperRegistry(List.of(numericDocumentMapper("PATENT"))));
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-candidate-width"),
                Map.of("query", "体替代印花", "topK", 5, "candidateTopN", 1));

        assertTrue(result.success());
        KnowledgeRetrievalCapability.Output output = (KnowledgeRetrievalCapability.Output) result.data();
        assertEquals(3, output.evidences().size(), "证据深度不能被 candidateTopN 一起裁掉");
        assertEquals(List.of(66L), output.candidateEntityIds(), "只向下游暴露排名第一的候选实体");
        assertEquals(3, result.metadata().get("rankedCandidateEntityCount"));
        assertEquals(1, result.metadata().get("candidateEntityCount"));
        assertEquals(1, result.metadata().get("candidateTopN"));
        assertEquals(true, result.metadata().get("candidateScopeLimited"));
        assertTrue(output.verifiedEntityIds().isEmpty());
        capability.shutdown();
    }

    @Test
    void candidateTopNMustBeBoundedInteger() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(retriever);

        CapabilityArgumentValidation invalidZero = capability.validateArguments(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-validation"),
                Map.of("query", "q", "candidateTopN", 0));
        CapabilityArgumentValidation invalidType = capability.validateArguments(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-validation"),
                Map.of("query", "q", "candidateTopN", "1"));
        CapabilityArgumentValidation valid = capability.validateArguments(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-validation"),
                Map.of("query", "q", "candidateTopN", 1));

        assertFalse(invalidZero.valid());
        assertFalse(invalidType.valid());
        assertTrue(valid.valid());
        capability.shutdown();
    }

    @Test
    void domainWithoutMapperKeepsSemanticCandidatesAsEvidenceOnly() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence candidate = Evidence.builder().chunkId(201L).documentId("74").content("候选").build();
        when(retriever.search("q", List.of(), List.of(6L), null, 8, 1L, 2L,
                "CONTRACT", "ag-no-map"))
                .thenReturn(new PlannedEvidenceRetriever.Result(List.of(candidate), null, null, null, null));

        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "CONTRACT", "ag-no-map"), Map.of("query", "q"));
        KnowledgeRetrievalCapability.Output output = (KnowledgeRetrievalCapability.Output) result.data();

        assertTrue(output.candidateEntityIds().isEmpty());
        assertTrue(output.verifiedEntityIds().isEmpty());
        assertEquals(false, result.metadata().get("candidateEntityMapped"));
        capability.shutdown();
    }

    @Test
    void scopeBlockedIsExplicitOutcomeNotAuthoritativeEmpty() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        when(retriever.search("q", List.of(), List.of(6L), null, 8, 1L, 2L,
                "PATENT", "ag-blocked"))
                .thenReturn(new PlannedEvidenceRetriever.Result(
                        PlannedEvidenceRetriever.Status.BLOCKED, "exact identifier resolved to no document",
                        List.of(), null, null, null, null, null));

        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-blocked"), Map.of("query", "q"));

        assertTrue(result.success());
        KnowledgeRetrievalCapability.Output output = (KnowledgeRetrievalCapability.Output) result.data();
        assertEquals("SCOPE_BLOCKED", output.retrievalOutcome());
        assertEquals(1, result.metadata().get("blockedSubqueryCount"));
        assertEquals(true, result.metadata().get("scopeBlocked"));
        assertEquals(false, result.metadata().get("authoritativeEmpty"));
        capability.shutdown();
    }

    @Test
    void contextScopeWithoutVerifiedEntitiesMustNotSearch() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-retrieval-empty"),
                Map.of("query", "它用了什么技术？", "scope", "CONTEXT"));

        assertFalse(result.success());
        assertEquals(AgentStopReason.NEED_USER_INPUT, result.stopReason());
        capability.shutdown();
    }

    private Evidence evidence(Long chunkId, String documentId, String name, Double score) {
        return Evidence.builder().chunkId(chunkId).documentId(documentId).documentName(name)
                .content(name).score(score).products(List.of()).channels(List.of("vector")).build();
    }

    private DomainEvidenceEntityMapper numericDocumentMapper(String domainCode) {
        return new DomainEvidenceEntityMapper() {
            @Override public String domainCode() { return domainCode; }
            @Override
            public Long candidateEntityId(Evidence evidence) {
                if (evidence == null || evidence.getDocumentId() == null) return null;
                try { return Long.parseLong(evidence.getDocumentId()); }
                catch (Exception e) { return null; }
            }
        };
    }
}
