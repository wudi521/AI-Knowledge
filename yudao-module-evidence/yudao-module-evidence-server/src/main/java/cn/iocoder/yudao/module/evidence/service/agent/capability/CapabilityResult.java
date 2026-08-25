package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CapabilityResult(boolean success,
                               Object data,
                               Map<String, Object> metadata,
                               AgentStopReason stopReason,
                               String message) {
    public CapabilityResult {
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static CapabilityResult success(Object data, Map<String, Object> metadata) {
        return new CapabilityResult(true, data, metadata, null, null);
    }

    public static CapabilityResult failure(AgentStopReason stopReason, String message) {
        return new CapabilityResult(false, null, Collections.emptyMap(), stopReason, message);
    }
}
