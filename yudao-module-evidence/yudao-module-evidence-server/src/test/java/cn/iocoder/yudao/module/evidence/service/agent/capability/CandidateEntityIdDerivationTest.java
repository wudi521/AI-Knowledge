package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEntityIdDerivationTest {

    @Test
    void publicOutputMustNotGuessDocumentIdAsBusinessEntity() {
        AgentCapabilityOutput output = new AgentCapabilityOutput() {
            @Override public String summary() { return "retrieval evidence"; }
            @Override public String progressHash() { return "retrieval-evidence-v1"; }
            @Override
            public List<Evidence> evidences() {
                return List.of(Evidence.builder().documentId("123").content("a").build());
            }
        };

        assertTrue(output.candidateEntityIds().isEmpty(),
                "Document is a knowledge carrier; public Runtime must not guess business entity identity");
        assertTrue(output.verifiedEntityIds().isEmpty());
    }

    @Test
    void domainMapperExplicitlyConvertsEvidenceToCandidateEntities() {
        DomainEvidenceEntityMapper mapper = new DomainEvidenceEntityMapper() {
            @Override public String domainCode() { return "PATENT"; }
            @Override
            public Long candidateEntityId(Evidence evidence) {
                if (evidence == null || evidence.getDocumentId() == null) return null;
                try {
                    long id = Long.parseLong(evidence.getDocumentId());
                    return id > 0 ? id : null;
                } catch (Exception e) {
                    return null;
                }
            }
        };
        DomainEvidenceEntityMapperRegistry registry = new DomainEvidenceEntityMapperRegistry(List.of(mapper));
        List<Evidence> evidences = List.of(
                Evidence.builder().documentId("123").content("a").build(),
                Evidence.builder().documentId("123").content("duplicate").build(),
                Evidence.builder().documentId("456").content("b").build(),
                Evidence.builder().documentId("not-an-entity-id").content("ignored").build());

        assertEquals(List.of(123L, 456L), registry.candidateEntityIds("PATENT", evidences));
        assertTrue(registry.candidateEntityIds("CONTRACT", evidences).isEmpty(),
                "a mapper registered for one domain must never leak into another domain");
    }
}
