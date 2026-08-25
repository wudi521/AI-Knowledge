package cn.iocoder.yudao.module.knowledge.service.agent.capability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册表。第一阶段只负责稳定注册和按名称读取；领域/权限过滤会在后续接入业务能力时扩展。
 */
@Component
public class CapabilityRegistry {

    private final Map<String, KnowledgeCapability> capabilities;

    public CapabilityRegistry(List<KnowledgeCapability> capabilityList) {
        Map<String, KnowledgeCapability> map = new LinkedHashMap<>();
        if (capabilityList != null) {
            for (KnowledgeCapability capability : capabilityList) {
                String name = capability.definition().name();
                if (map.putIfAbsent(name, capability) != null) {
                    throw new IllegalStateException("duplicate capability: " + name);
                }
            }
        }
        this.capabilities = Collections.unmodifiableMap(map);
    }

    public KnowledgeCapability get(String name) {
        return capabilities.get(name);
    }

    public List<CapabilityDefinition> listDefinitions() {
        List<CapabilityDefinition> definitions = new ArrayList<>(capabilities.size());
        for (KnowledgeCapability capability : capabilities.values()) {
            definitions.add(capability.definition());
        }
        return Collections.unmodifiableList(definitions);
    }
}
