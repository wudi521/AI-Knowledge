package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CapabilityRegistry {
    private final Map<String, KnowledgeCapability> capabilities;
    private final List<CapabilityVisibilityPolicy> visibilityPolicies;

    public CapabilityRegistry(List<KnowledgeCapability> capabilityList,
                              List<CapabilityVisibilityPolicy> visibilityPolicies) {
        Map<String, KnowledgeCapability> map = new LinkedHashMap<>();
        if (capabilityList != null) {
            for (KnowledgeCapability capability : capabilityList) {
                String name = capability.definition().name();
                if (map.putIfAbsent(name, capability) != null) throw new IllegalStateException("duplicate capability: " + name);
            }
        }
        this.capabilities = Collections.unmodifiableMap(map);
        this.visibilityPolicies = visibilityPolicies == null ? List.of() : List.copyOf(visibilityPolicies);
    }

    public KnowledgeCapability getVisible(String name, CapabilityInvocationContext context) {
        KnowledgeCapability capability = capabilities.get(name);
        if (capability == null) return null;
        return isVisible(capability.definition(), context) ? capability : null;
    }

    public List<CapabilityDefinition> listDefinitions(CapabilityInvocationContext context) {
        List<CapabilityDefinition> out = new ArrayList<>();
        for (KnowledgeCapability capability : capabilities.values()) {
            if (isVisible(capability.definition(), context)) out.add(capability.definition());
        }
        return Collections.unmodifiableList(out);
    }

    private boolean isVisible(CapabilityDefinition definition, CapabilityInvocationContext context) {
        for (CapabilityVisibilityPolicy policy : visibilityPolicies) {
            if (!policy.isVisible(definition, context)) return false;
        }
        return true;
    }
}
