package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityFailureType;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Auditable record of one Runtime node execution. */
public record ActivityRecord(String planId,
                             String nodeId,
                             String capability,
                             CapabilityResultStatus status,
                             CapabilityFailureType failureType,
                             long startedAtMs,
                             long elapsedMs,
                             String message,
                             Map<String, Object> metadata) {
    public ActivityRecord {
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
