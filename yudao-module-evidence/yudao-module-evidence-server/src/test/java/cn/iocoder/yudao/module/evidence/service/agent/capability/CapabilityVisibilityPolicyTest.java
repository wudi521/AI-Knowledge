package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityVisibilityPolicyTest {

    @Test
    void domainPermissionAndKbCapabilityMustAllMatch() {
        EvidenceProperties properties = new EvidenceProperties();
        DefaultCapabilityVisibilityPolicy policy = new DefaultCapabilityVisibilityPolicy(properties);
        CapabilityDefinition definition = definition(true);

        CapabilityInvocationContext allowed = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of("patent:read"), Set.of("structured"), List.of(), "test", false);
        assertTrue(policy.isVisible(definition, allowed));

        CapabilityInvocationContext wrongDomain = new CapabilityInvocationContext(
                1L, 2L, 6L, "PRODUCT", "trace",
                Set.of("patent:read"), Set.of("structured"), List.of(), "test", false);
        assertFalse(policy.isVisible(definition, wrongDomain));

        CapabilityInvocationContext missingPermission = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of(), Set.of("structured"), List.of(), "test", false);
        assertFalse(policy.isVisible(definition, missingPermission));

        CapabilityInvocationContext missingKbCapability = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of("patent:read"), Set.of(), List.of(), "test", false);
        assertFalse(policy.isVisible(definition, missingKbCapability));
    }

    @Test
    void writeCapabilityMustRequireBothConfigAndRequestPermission() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setWriteAllowed(true);
        DefaultCapabilityVisibilityPolicy policy = new DefaultCapabilityVisibilityPolicy(properties);
        CapabilityDefinition writeDefinition = definition(false);

        CapabilityInvocationContext requestDenied = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of("patent:read"), Set.of("structured"), List.of(), "test", false);
        assertFalse(policy.isVisible(writeDefinition, requestDenied));

        CapabilityInvocationContext allowed = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of("patent:read"), Set.of("structured"), List.of(), "test", true);
        assertTrue(policy.isVisible(writeDefinition, allowed));
    }

    @Test
    void enabledAndDisabledCapabilitySwitchesMustBeAppliedBeforePlannerSeesTools() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setEnabledCapabilities(Set.of("governed"));
        DefaultCapabilityVisibilityPolicy policy = new DefaultCapabilityVisibilityPolicy(properties);
        CapabilityDefinition definition = definition(true);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace",
                Set.of("patent:read"), Set.of("structured"), List.of(), "test", false);
        assertTrue(policy.isVisible(definition, context));

        properties.getAgent().setDisabledCapabilities(Set.of("governed"));
        assertFalse(policy.isVisible(definition, context));
    }

    private CapabilityDefinition definition(boolean readOnly) {
        return new CapabilityDefinition(
                "governed", "1", "治理测试能力",
                Map.of("query", "test"), Set.of("query"), "TEST", readOnly,
                Set.of("patent:read"), Set.of("PATENT"), Set.of("structured"),
                1000, 10);
    }
}
