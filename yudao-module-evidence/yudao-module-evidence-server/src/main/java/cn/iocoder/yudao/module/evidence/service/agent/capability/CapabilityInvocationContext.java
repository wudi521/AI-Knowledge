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

    /**
     * 同一 Agent 请求内，只有能力返回的 verifiedEntityIds 才能扩充这里的 trusted scope。
     * 其他系统范围保持服务端原值，Planner 无权覆盖。
     */
    public CapabilityInvocationContext withContextEntityIds(List<Long> entityIds) {
        return new CapabilityInvocationContext(tenantId, userId, kbId, domainCode, traceId,
                permissions, kbCapabilities, entityIds, environment, writeAllowed);
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
