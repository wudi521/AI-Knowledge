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

    /**
     * Planner 可以在剩余预算内修正的调用错误。只用于参数/组合契约错误；权限、超时、完整性失败不得标记可修复。
     */
    public static CapabilityResult recoverableFailure(String message, Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recoverable", true);
        if (details != null) metadata.putAll(details);
        return new CapabilityResult(false, null, metadata, AgentStopReason.INVALID_CAPABILITY_CALL, message);
    }

    public boolean recoverable() {
        return !success && Boolean.TRUE.equals(metadata.get("recoverable"));
    }
}
