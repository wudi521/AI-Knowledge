package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatentClaimLookupCapabilityTest {

    @Test
    void exactClaimRequiresOneTrustedPatentEntity() {
        PatentClaimLookupCapability capability = new PatentClaimLookupCapability(mock(PlannedEvidenceRetriever.class));
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of(), Set.of(), List.of(), "test", false);
        CapabilityResult result = capability.execute(context, Map.of("claimNo", 4, "mode", "RAW"));
        assertFalse(result.success());
        assertEquals(AgentStopReason.NEED_USER_INPUT, result.stopReason());
    }

    @Test
    void dependencyUsesClaimMetadataDeterministically() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence claim = Evidence.builder()
                .chunkId(400L).documentId("74").documentName("测试专利")
                .content("根据权利要求1和2所述的装置……")
                .chunkMetadata("{\"domainCode\":\"PATENT\",\"sectionType\":\"CLAIMS\",\"claimNo\":4,\"dependsOn\":[1,2]}")
                .score(1D).products(List.of()).channels(List.of("bm25")).build();
        when(retriever.search(anyString(), anyList(), anyList(), anyList(), anyInt(), anyLong(), anyLong(), anyString()))
                .thenReturn(new PlannedEvidenceRetriever.Result(List.of(claim), null, null, 1L, true));

        PatentClaimLookupCapability capability = new PatentClaimLookupCapability(retriever);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of(), Set.of(), List.of(74L), "test", false);
        CapabilityResult result = capability.execute(context, Map.of("claimNo", 4, "mode", "DEPENDENCY"));
        assertTrue(result.success());
        AgentCapabilityOutput output = (AgentCapabilityOutput) result.data();
        assertTrue(output.deterministicAnswer().contains("1、2"));
        assertTrue(output.deterministicAnswer().contains("[C1]"));
    }

    @Test
    void conflictingClaimContentsMustFailClosed() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        Evidence a = claim(401L, "内容A");
        Evidence b = claim(402L, "内容B");
        when(retriever.search(anyString(), anyList(), anyList(), anyList(), anyInt(), anyLong(), anyLong(), anyString()))
                .thenReturn(new PlannedEvidenceRetriever.Result(List.of(a, b), null, null, 2L, true));

        PatentClaimLookupCapability capability = new PatentClaimLookupCapability(retriever);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of(), Set.of(), List.of(74L), "test", false);
        CapabilityResult result = capability.execute(context, Map.of("claimNo", 4, "mode", "RAW"));
        assertFalse(result.success());
        assertEquals(AgentStopReason.NO_RELIABLE_EVIDENCE, result.stopReason());
    }

    private Evidence claim(Long chunkId, String content) {
        return Evidence.builder().chunkId(chunkId).documentId("74").documentName("测试专利")
                .content(content)
                .chunkMetadata("{\"domainCode\":\"PATENT\",\"sectionType\":\"CLAIMS\",\"claimNo\":4,\"dependsOn\":[]}")
                .score(1D).products(List.of()).channels(List.of("bm25")).build();
    }
}
