package cn.iocoder.yudao.module.evidence.service.agent.capability;

/**
 * Runtime 统一失败分类。只有 TIMEOUT / THROTTLED / TRANSIENT 可以由 Runtime 自动重试。
 */
public enum CapabilityFailureType {
    VALIDATION(false),
    PERMISSION(false),
    CONFIGURATION(false),
    TIMEOUT(true),
    THROTTLED(true),
    TRANSIENT(true),
    DEPENDENCY(false),
    DATA_INCOMPLETE(false);

    private final boolean retryable;

    CapabilityFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
