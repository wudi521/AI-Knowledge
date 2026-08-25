package cn.iocoder.yudao.module.evidence.service.agent;

/** 预算、重复调用等硬防线由代码控制，不能依靠 prompt。 */
public final class AgentExecutionGuard {
    private final AgentExecutionBudget budget;

    public AgentExecutionGuard(AgentExecutionBudget budget) {
        this.budget = budget;
    }

    public GuardResult beforePlannerCall(AgentExecutionState state) {
        GuardResult common = commonCheck(state);
        if (!common.allowed()) return common;
        if (state.getLlmCalls() >= budget.maxLlmCalls()) return deny(AgentStopReason.MAX_LLM_CALLS);
        return GuardResult.allow();
    }

    public GuardResult beforeCapabilityCall(AgentExecutionState state, String fingerprint) {
        GuardResult common = commonCheck(state);
        if (!common.allowed()) return common;
        if (state.getStep() >= budget.maxSteps()) return deny(AgentStopReason.MAX_STEPS);
        if (fingerprint != null && state.hasCapabilityCallFingerprint(fingerprint)) return deny(AgentStopReason.REPEATED_CALL);
        return GuardResult.allow();
    }

    private GuardResult commonCheck(AgentExecutionState state) {
        if (state.isStopped()) return deny(state.getStopReason());
        if (state.elapsedMs() >= budget.maxElapsedMs()) return deny(AgentStopReason.TIME_BUDGET_EXCEEDED);
        return GuardResult.allow();
    }

    private GuardResult deny(AgentStopReason reason) { return new GuardResult(false, reason); }

    public record GuardResult(boolean allowed, AgentStopReason stopReason) {
        public static GuardResult allow() { return new GuardResult(true, null); }
    }
}
