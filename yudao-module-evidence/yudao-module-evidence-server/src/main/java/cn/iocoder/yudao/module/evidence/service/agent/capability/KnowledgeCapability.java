package cn.iocoder.yudao.module.evidence.service.agent.capability;

import java.util.Map;

public interface KnowledgeCapability {
    /** 稳定的机器执行契约。 */
    CapabilityDefinition definition();

    /**
     * 给 Planner 看的上下文契约。默认与机器契约一致；动态能力可以只收窄描述/可选值，
     * 例如按当前 Domain 暴露真实 relationType。执行端仍必须以 definition + validateArguments 做硬校验。
     */
    default CapabilityDefinition plannerDefinition(CapabilityInvocationContext context) {
        return definition();
    }

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
