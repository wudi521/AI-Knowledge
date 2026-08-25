package cn.iocoder.yudao.module.evidence.service.agent.capability;

/** 在能力送给 Planner 前做上下文可见性过滤。 */
public interface CapabilityVisibilityPolicy {
    boolean isVisible(CapabilityDefinition definition, CapabilityInvocationContext context);
}
