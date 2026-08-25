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

    /**
     * 返回“机器真正准备执行的语义计划”的稳定键。
     *
     * <p>默认返回 null，Invoker 会退回到原始参数指纹。组合式能力应覆盖本方法，先把不同 JSON 写法
     * 规范化成同一个执行计划，再做重复调用检测。这样重复保护依据的是执行语义，而不是 LLM 输出文本。</p>
     */
    default String canonicalExecutionKey(CapabilityInvocationContext context,
                                         Map<String, Object> arguments) {
        return null;
    }

    CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments);
}
