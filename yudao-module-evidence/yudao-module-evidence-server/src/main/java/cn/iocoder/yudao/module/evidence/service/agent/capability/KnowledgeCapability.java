package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Map;

public interface KnowledgeCapability {
    CapabilityDefinition definition();
    CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments);
}
