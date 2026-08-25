package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 对 Planner 可见的能力契约。 */
public record CapabilityDefinition(String name,
                                   String version,
                                   String description,
                                   Map<String, String> argumentSchema,
                                   Set<String> requiredArguments,
                                   String outputType,
                                   boolean readOnly,
                                   Set<String> requiredPermissions,
                                   Set<String> supportedDomains,
                                   Set<String> requiredKbCapabilities,
                                   long timeoutMs,
                                   int maxRows) {
    public CapabilityDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description must not be blank");
        argumentSchema = argumentSchema == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(argumentSchema));
        requiredArguments = immutable(requiredArguments);
        outputType = outputType == null || outputType.isBlank() ? "OBJECT" : outputType;
        requiredPermissions = immutable(requiredPermissions);
        supportedDomains = immutableUpper(supportedDomains);
        requiredKbCapabilities = immutable(requiredKbCapabilities);
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        if (maxRows <= 0) throw new IllegalArgumentException("maxRows must be > 0");
    }

    /** 兼容第一纵切的旧构造器；新能力应优先声明完整契约。 */
    public CapabilityDefinition(String name, String version, String description,
                                Set<String> requiredArguments, boolean readOnly,
                                long timeoutMs, int maxRows) {
        this(name, version, description, Map.of(), requiredArguments, "OBJECT", readOnly,
                Set.of(), Set.of(), Set.of(), timeoutMs, maxRows);
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static Set<String> immutableUpper(Set<String> source) {
        if (source == null || source.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String value : source) if (value != null && !value.isBlank()) out.add(value.trim().toUpperCase());
        return Collections.unmodifiableSet(out);
    }
}
