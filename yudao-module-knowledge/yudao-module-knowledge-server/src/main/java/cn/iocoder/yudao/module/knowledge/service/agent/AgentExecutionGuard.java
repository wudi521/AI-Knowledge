package cn.iocoder.yudao.module.knowledge.service.agent;

/**
 * Agent 执行硬防线：预算、重复调用和停止状态都由代码控制，而不是交给模型自行遵守。
 */
public final class AgentExecutionGuard {

    private final AgentExecutionBudget budget;

    public AgentExecutionGuard(AgentExecutionBudget budget) {
        this.budget = budget;
    }

    public GuardResult beforePlannerCall(AgentExecutionState state) {
        GuardResult common = commonCheck(state);
        if (!common.allowed()) {
            return common;
        }
        if (state.getLlmCalls() >= budget.maxLlmCalls()) {
            return deny(AgentStopReason.MAX_LLM_CALLS);
        }
        return GuardResult.allow();
    }

    public GuardResult beforeCapabilityCall(AgentExecutionState state, String callFingerprint) {
        GuardResult common = commonCheck(state);
        if (!common.allowed()) {
            return common;
        }
        if (state.getStep() >= budget.maxSteps()) {
            return deny(AgentStopReason.MAX_STEPS);
        }
        if (callFingerprint != null && state.hasCapabilityCallFingerprint(callFingerprint)) {
            return deny(AgentStopReason.REPEATED_CALL);
        }
        return GuardResult.allow();
    }

    private GuardResult commonCheck(AgentExecutionState state) {
        if (state.isStopped()) {
            return deny(state.getStopReason());
        }
        if (state.elapsedMs() >= budget.maxElapsedMs()) {
            return deny(AgentStopReason.TIME_BUDGET_EXCEEDED);
        }
        return GuardResult.allow();
    }

    private GuardResult deny(AgentStopReason stopReason) {
        return new GuardResult(false, stopReason);
    }

    public record GuardResult(boolean allowed, AgentStopReason stopReason) {
        public static GuardResult allow() {
            return new GuardResult(true, null);
        }
    }
}
