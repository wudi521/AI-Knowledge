package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEntityIdDerivationTest {

    @Test
    void evidenceDocumentIdsBecomeCandidatesButNeverVerifiedByDefault() {
        AgentCapabilityOutput output = new AgentCapabilityOutput() {
            @Override public String summary() { return "retrieval candidates"; }
            @Override public String progressHash() { return "retrieval-candidates-v1"; }
            @Override
            public List<Evidence> evidences() {
                return List.of(
                        Evidence.builder().documentId("123").content("a").build(),
                        Evidence.builder().documentId("123").content("duplicate").build(),
                        Evidence.builder().documentId("456").content("b").build(),
                        Evidence.builder().documentId("not-an-entity-id").content("ignored").build());
            }
        };

        assertEquals(List.of(123L, 456L), output.candidateEntityIds());
        assertTrue(output.verifiedEntityIds().isEmpty(),
                "retrieval evidence must not become trusted merely because document ids are available");
    }
}
