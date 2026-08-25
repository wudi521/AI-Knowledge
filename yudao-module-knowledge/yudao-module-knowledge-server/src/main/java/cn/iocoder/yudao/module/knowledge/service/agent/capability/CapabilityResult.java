package cn.iocoder.yudao.module.knowledge.service.agent.capability;

import cn.iocoder.yudao.module.knowledge.service.agent.AgentStopReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一的能力执行结果。 */
public final class CapabilityResult {

    private final boolean success;
    private final Object data;
    private final Map<String, Object> metadata;
    private final AgentStopReason stopReason;
    private final String message;

    private CapabilityResult(boolean success, Object data, Map<String, Object> metadata,
                             AgentStopReason stopReason, String message) {
        this.success = success;
        this.data = data;
        this.metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.stopReason = stopReason;
        this.message = message;
    }

    public static CapabilityResult success(Object data, Map<String, Object> metadata) {
        return new CapabilityResult(true, data, metadata, null, null);
    }

    public static CapabilityResult failure(AgentStopReason stopReason, String message) {
        return new CapabilityResult(false, null, Collections.emptyMap(), stopReason, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getData() {
        return data;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public AgentStopReason getStopReason() {
        return stopReason;
    }

    public String getMessage() {
        return message;
    }
}
