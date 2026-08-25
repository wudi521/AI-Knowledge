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
    void semanticCandidatesMustRemainEvidenceNotTrustedEntities() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence candidate = Evidence.builder()
                .chunkId(201L).documentId("74").documentName("倾转小翼垂直起降固定翼无人机")
                .content("一种无人机技术方案").score(1D).products(List.of()).channels(List.of("vector"))
                .build();
        when(retriever.search("垂直起降无人机技术", List.of("无人机垂直起降技术"),
                List.of(6L), null, 8, 1L, 2L, "ag-retrieval"))
                .thenReturn(new PlannedEvidenceRetriever.Result(List.of(candidate), null, null, null, null));

        KnowledgeRetrievalCapability capability = new KnowledgeRetrievalCapability(retriever);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-retrieval"),
                Map.of("query", "垂直起降无人机技术", "variants", List.of("无人机垂直起降技术"), "topK", 8));

        assertTrue(result.success());
        KnowledgeRetrievalCapability.Output output = (KnowledgeRetrievalCapability.Output) result.data();
        assertEquals(1, output.evidences().size());
        assertTrue(output.verifiedEntityIds().isEmpty(), "普通语义候选绝不能升级为 trusted entity");
        assertEquals("74", output.evidences().get(0).getDocumentId());
        verify(retriever).search("垂直起降无人机技术", List.of("无人机垂直起降技术"),
                List.of(6L), null, 8, 1L, 2L, "ag-retrieval");
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
    }
}
