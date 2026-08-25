package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Map;

public interface KnowledgeCapability {
    CapabilityDefinition definition();

    /**
     * 机器可执行的参数契约校验。默认仅依赖 CapabilityDefinition 的白名单/必填校验；
     * 复杂能力可覆盖本方法继续校验 JSON 形状、类型、范围和参数组合。
     */
    default CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                           Map<String, Object> arguments) {
        return CapabilityArgumentValidation.ok();
    }

    CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments);
}
