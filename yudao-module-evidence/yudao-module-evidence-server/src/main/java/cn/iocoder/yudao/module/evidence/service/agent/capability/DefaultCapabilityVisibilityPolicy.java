package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 默认能力可见性策略：领域、知识库能力、权限、环境开关、只读约束统一在送给 Planner 前裁剪。
 */
@Component
public class DefaultCapabilityVisibilityPolicy implements CapabilityVisibilityPolicy {
    private final EvidenceProperties properties;

    public DefaultCapabilityVisibilityPolicy(EvidenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isVisible(CapabilityDefinition definition, CapabilityInvocationContext context) {
        if (definition == null || context == null || context.userId() == null || context.kbId() == null) return false;
        EvidenceProperties.Agent agent = properties == null ? null : properties.getAgent();
        Set<String> disabled = agent == null ? Set.of() : agent.getDisabledCapabilities();
        if (disabled != null && disabled.contains(definition.name())) return false;
        Set<String> enabled = agent == null ? Set.of() : agent.getEnabledCapabilities();
        if (enabled != null && !enabled.isEmpty() && !enabled.contains(definition.name())) return false;

        if (!definition.supportedDomains().isEmpty()) {
            String domain = context.domainCode() == null ? "" : context.domainCode().trim().toUpperCase(Locale.ROOT);
            if (!definition.supportedDomains().contains(domain)) return false;
        }
        if (!context.permissions().containsAll(definition.requiredPermissions())) return false;
        if (!context.kbCapabilities().containsAll(definition.requiredKbCapabilities())) return false;
        if (!definition.readOnly()) {
            boolean configuredWrite = agent != null && agent.isWriteAllowed();
            if (!configuredWrite || !context.writeAllowed()) return false;
        }
        return true;
    }
}
