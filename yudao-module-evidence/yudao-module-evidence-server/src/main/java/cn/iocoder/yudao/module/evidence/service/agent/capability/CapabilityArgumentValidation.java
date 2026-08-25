package cn.iocoder.yudao.module.evidence.service.agent.capability;

/**
 * Capability 的机器参数契约校验结果。
 * 参数形状/类型/范围错误属于可修复的调用契约错误；系统 scope/权限错误不走这里。
 */
public record CapabilityArgumentValidation(boolean valid, String message) {
    public static CapabilityArgumentValidation ok() {
        return new CapabilityArgumentValidation(true, null);
    }

    public static CapabilityArgumentValidation invalid(String message) {
        return new CapabilityArgumentValidation(false, message);
    }
}
