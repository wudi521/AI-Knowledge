package cn.iocoder.yudao.module.evidence.service.agent.capability;

/**
 * Typed Tool 执行结果的公共状态。
 *
 * <p>它描述的是一次事实操作的结果形态，不代表 OriginalGoal 是否已经满足。
 * SATISFIED / INSUFFICIENT / NEED_INFO 属于 Goal Evaluator，而不是 Tool Result。</p>
 */
public enum CapabilityResultStatus {
    SUCCESS,
    PARTIAL,
    EMPTY,
    FAILED
}
