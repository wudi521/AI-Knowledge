package cn.iocoder.yudao.module.evidence.service.agent.runtime;

/** Structured Query Planning output. */
public record AgentPlanningDecision(Action action,
                                    AgentExecutionPlan executionPlan,
                                    String message) {
    public enum Action {
        EXECUTE_PLAN,
        ANSWER,
        NEED_MORE_INFO,
        STOP
    }

    public static AgentPlanningDecision execute(AgentExecutionPlan plan) {
        return new AgentPlanningDecision(Action.EXECUTE_PLAN, plan, null);
    }

    public static AgentPlanningDecision answer() {
        return new AgentPlanningDecision(Action.ANSWER, null, null);
    }

    public static AgentPlanningDecision needInfo(String message) {
        return new AgentPlanningDecision(Action.NEED_MORE_INFO, null, message);
    }

    public static AgentPlanningDecision stop(String message) {
        return new AgentPlanningDecision(Action.STOP, null, message);
    }
}
