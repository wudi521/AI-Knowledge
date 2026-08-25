package cn.iocoder.yudao.module.knowledge.service.agent.capability;

import java.util.Map;

/** Agent 可调用的受控知识能力。 */
public interface KnowledgeCapability {

    CapabilityDefinition definition();

    CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments);
}
