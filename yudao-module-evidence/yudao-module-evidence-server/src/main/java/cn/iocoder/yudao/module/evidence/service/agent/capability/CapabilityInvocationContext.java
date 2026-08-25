package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 由服务端注入；Planner 无权指定或覆盖。 */
public record CapabilityInvocationContext(Long tenantId,
                                          Long userId,
                                          Long kbId,
                                          String domainCode,
                                          String traceId,
                                          Set<String> permissions,
                                          Set<String> kbCapabilities,
                                          List<Long> contextEntityIds,
                                          String environment,
                                          boolean writeAllowed) {
    public CapabilityInvocationContext {
        permissions = immutable(permissions);
        kbCapabilities = immutable(kbCapabilities);
        contextEntityIds = contextEntityIds == null ? List.of() : List.copyOf(contextEntityIds);
        environment = environment == null || environment.isBlank() ? "default" : environment.trim();
    }

    public CapabilityInvocationContext(Long tenantId, Long userId, Long kbId,
                                       String domainCode, String traceId) {
        this(tenantId, userId, kbId, domainCode, traceId,
                Set.of(), Set.of(), List.of(), "default", false);
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
