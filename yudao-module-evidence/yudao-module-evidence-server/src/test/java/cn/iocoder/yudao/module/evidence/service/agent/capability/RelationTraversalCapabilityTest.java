package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationTraversalCapabilityTest {

    @Test
    void registeredDomainProviderExecutesThroughGenericRelationToolAndPublishesTypedContract() {
        DomainRelationProvider provider = new DomainRelationProvider() {
            @Override public String domainCode() { return "PATENT"; }
            @Override public Set<String> relationTypes() { return Set.of("CITES", "PRIORITY"); }
            @Override
            public RelationResult traverse(RelationRequest request) {
                assertEquals(List.of(1L, 2L), request.sourceEntityIds());
                assertEquals("CITES", request.relationType());
                return RelationResult.complete(Map.of(1L, List.of(10L), 2L, List.of(11L)), List.of());
            }
        };
        RelationTraversalCapability capability = new RelationTraversalCapability(List.of(provider));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-rel");

        CapabilityDefinition plannerDefinition = capability.plannerDefinition(context);
        assertTrue(plannerDefinition.argumentSchema().get("relationType").contains("CITES"));
        assertTrue(plannerDefinition.argumentSchema().get("relationType").contains("PRIORITY"));

        CapabilityResult result = capability.execute(context, Map.of(
                "sourceEntityIds", List.of(1L, 2L),
                "relationType", "CITES",
                "direction", "OUT"));

        assertEquals(CapabilityResultStatus.SUCCESS, result.status());
        RelationTraversalCapability.Output output = (RelationTraversalCapability.Output) result.data();
        assertEquals(List.of(10L, 11L), output.verifiedEntityIds());
        assertTrue(Boolean.TRUE.equals(result.metadata().get("completeDataset")));
    }

    @Test
    void completeEmptyRelationIsTypedEmpty() {
        DomainRelationProvider provider = new DomainRelationProvider() {
            @Override public String domainCode() { return "PATENT"; }
            @Override public Set<String> relationTypes() { return Set.of("CITES"); }
            @Override public RelationResult traverse(RelationRequest request) {
                return RelationResult.complete(Map.of(1L, List.of()), List.of());
            }
        };
        RelationTraversalCapability capability = new RelationTraversalCapability(List.of(provider));
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-rel-empty"),
                Map.of("sourceEntityIds", List.of(1L), "relationType", "CITES"));

        assertEquals(CapabilityResultStatus.EMPTY, result.status());
        assertTrue(Boolean.TRUE.equals(result.metadata().get("authoritativeEmpty")));
    }

    @Test
    void relationToolIsInvisibleWhenNoProviderExistsForDomain() {
        RelationTraversalCapability capability = new RelationTraversalCapability(List.of());
        CapabilityRegistry registry = new CapabilityRegistry(List.of(capability),
                List.of(new DefaultCapabilityVisibilityPolicy(new EvidenceProperties())));

        KnowledgeCapability visible = registry.getVisible(RelationTraversalCapability.NAME,
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-no-provider"));

        assertNull(visible);
    }
}
