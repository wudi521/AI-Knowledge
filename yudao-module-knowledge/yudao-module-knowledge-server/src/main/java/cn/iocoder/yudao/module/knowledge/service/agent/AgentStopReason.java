package cn.iocoder.yudao.module.knowledge.service.agent;

/** Agent 执行停止原因。 */
public enum AgentStopReason {
    ENOUGH_EVIDENCE,
    NEED_USER_INPUT,
    NO_RELIABLE_EVIDENCE,
    CAPABILITY_UNAVAILABLE,
    MAX_STEPS,
    MAX_LLM_CALLS,
    TIME_BUDGET_EXCEEDED,
    REPEATED_CALL,
    NO_PROGRESS,
    INVALID_CAPABILITY_CALL,
    PERMISSION_DENIED
}
