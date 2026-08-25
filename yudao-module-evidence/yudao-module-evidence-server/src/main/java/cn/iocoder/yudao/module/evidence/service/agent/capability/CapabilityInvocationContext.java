package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 由服务端注入；Planner 无权指定或覆盖。 */
public record CapabilityInvocationContext(Long tenantId,
                                          Long userId,
                                          Long kbId,
                                          String domainCode,
                                          String traceId,
                                          Set<String> permissions,
                                          Set<String> kbCapabilities,
                                          String environment,
                                          boolean writeAllowed) {
    public CapabilityInvocationContext {
        permissions = immutable(permissions);
        kbCapabilities = immutable(kbCapabilities);
        environment = environment == null || environment.isBlank() ? "default" : environment.trim();
    }

    /** 兼容第一纵切调用；权限/环境信息由后续入口逐步注入。 */
    public CapabilityInvocationContext(Long tenantId, Long userId, Long kbId,
                                       String domainCode, String traceId) {
        this(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), "default", false);
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
