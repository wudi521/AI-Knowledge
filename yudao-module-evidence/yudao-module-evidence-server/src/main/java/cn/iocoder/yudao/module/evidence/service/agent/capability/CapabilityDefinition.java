package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 对 Planner 可见的能力契约。 */
public record CapabilityDefinition(String name,
                                   String version,
                                   String description,
                                   Set<String> requiredArguments,
                                   boolean readOnly,
                                   long timeoutMs,
                                   int maxRows) {
    public CapabilityDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description must not be blank");
        requiredArguments = requiredArguments == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredArguments));
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        if (maxRows <= 0) throw new IllegalArgumentException("maxRows must be > 0");
    }
}
