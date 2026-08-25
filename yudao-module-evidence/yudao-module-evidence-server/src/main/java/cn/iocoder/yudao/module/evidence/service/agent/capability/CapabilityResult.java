package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed Tool 的统一执行结果。
 *
 * <p>Tool 只描述事实操作本身是 SUCCESS / PARTIAL / EMPTY / FAILED；
 * 是否已经满足 OriginalGoal 由 Goal Evaluator 独立判断。</p>
 */
public record CapabilityResult(CapabilityResultStatus status,
                               Object data,
                               Map<String, Object> metadata,
                               AgentStopReason stopReason,
                               CapabilityFailureType failureType,
                               String message) {
    public CapabilityResult {
        status = status == null ? CapabilityResultStatus.FAILED : status;
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        if (status != CapabilityResultStatus.FAILED) {
            failureType = null;
            stopReason = null;
        }
    }

    /** 兼容旧调用方；新代码应优先使用强类型工厂方法。 */
    public CapabilityResult(boolean success,
                            Object data,
                            Map<String, Object> metadata,
                            AgentStopReason stopReason,
                            String message) {
        this(success ? CapabilityResultStatus.SUCCESS : CapabilityResultStatus.FAILED,
                data, metadata, success ? null : stopReason,
                success ? null : inferFailureType(stopReason), message);
    }

    /**
     * 保留旧 success() 语义：PARTIAL / EMPTY 都是一次成功执行，只是结果形态不同。
     */
    public boolean success() {
        return status != CapabilityResultStatus.FAILED;
    }

    public boolean partial() {
        return status == CapabilityResultStatus.PARTIAL;
    }

    public boolean empty() {
        return status == CapabilityResultStatus.EMPTY;
    }

    public static CapabilityResult success(Object data, Map<String, Object> metadata) {
        return new CapabilityResult(CapabilityResultStatus.SUCCESS, data, metadata, null, null, null);
    }

    public static CapabilityResult partial(Object data, String message, Map<String, Object> metadata) {
        return new CapabilityResult(CapabilityResultStatus.PARTIAL, data, metadata, null, null, message);
    }

    public static CapabilityResult empty(Map<String, Object> metadata) {
        return new CapabilityResult(CapabilityResultStatus.EMPTY, null, metadata, null, null, null);
    }

    public static CapabilityResult failure(AgentStopReason stopReason, String message) {
        return failure(stopReason, message, Collections.emptyMap());
    }

    /**
     * 兼容旧失败工厂。旧 stopReason 会映射为最保守的统一失败类型。
     */
    public static CapabilityResult failure(AgentStopReason stopReason, String message, Map<String, Object> metadata) {
        return failure(inferFailureType(stopReason), stopReason, message, metadata);
    }

    public static CapabilityResult failure(CapabilityFailureType failureType,
                                           AgentStopReason stopReason,
                                           String message) {
        return failure(failureType, stopReason, message, Collections.emptyMap());
    }

    public static CapabilityResult failure(CapabilityFailureType failureType,
                                           AgentStopReason stopReason,
                                           String message,
                                           Map<String, Object> metadata) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (metadata != null) safe.putAll(metadata);
        // Runtime retry 由 failureType 决定，禁止能力通过 metadata 任意打开重试。
        safe.remove("runtimeRetryable");
        safe.remove("recoverable");
        return new CapabilityResult(CapabilityResultStatus.FAILED, null, safe, stopReason,
                failureType == null ? CapabilityFailureType.DATA_INCOMPLETE : failureType, message);
    }

    /**
     * Planner 可以在剩余预算内修正的计划/参数契约错误。
     *
     * <p>这不是 Runtime retry。即使 plannerRecoverable=true，Runtime 也绝不会原样重试同一次调用。</p>
     */
    public static CapabilityResult recoverableFailure(String message, Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerRecoverable", true);
        // 兼容现有 observation / 测试读取；语义仍严格限定为 Planner 修正。
        metadata.put("recoverable", true);
        if (details != null) metadata.putAll(details);
        return new CapabilityResult(CapabilityResultStatus.FAILED, null, metadata,
                AgentStopReason.INVALID_CAPABILITY_CALL, CapabilityFailureType.VALIDATION, message);
    }

    /** Planner 修正通道；与 runtimeRetryable() 完全独立。 */
    public boolean recoverable() {
        return status == CapabilityResultStatus.FAILED
                && (Boolean.TRUE.equals(metadata.get("plannerRecoverable"))
                || Boolean.TRUE.equals(metadata.get("recoverable")));
    }

    /**
     * Runtime 原样重试通道。只有 TIMEOUT / THROTTLED / TRANSIENT 为 true。
     */
    public boolean runtimeRetryable() {
        return status == CapabilityResultStatus.FAILED
                && failureType != null
                && failureType.retryable();
    }

    private static CapabilityFailureType inferFailureType(AgentStopReason stopReason) {
        if (stopReason == null) return CapabilityFailureType.DATA_INCOMPLETE;
        return switch (stopReason) {
            case INVALID_CAPABILITY_CALL -> CapabilityFailureType.VALIDATION;
            case PERMISSION_DENIED -> CapabilityFailureType.PERMISSION;
            case CAPABILITY_UNAVAILABLE -> CapabilityFailureType.CONFIGURATION;
            case TIME_BUDGET_EXCEEDED -> CapabilityFailureType.TIMEOUT;
            case MAX_STEPS, MAX_LLM_CALLS, REPEATED_CALL, NO_PROGRESS -> CapabilityFailureType.CONFIGURATION;
            case NO_RELIABLE_EVIDENCE -> CapabilityFailureType.DATA_INCOMPLETE;
            case ENOUGH_EVIDENCE, NEED_USER_INPUT -> CapabilityFailureType.DATA_INCOMPLETE;
        };
    }
}
