package cn.iocoder.yudao.module.evidence.service.agent;

/** Agent 单次执行的结构化停止原因。 */
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
