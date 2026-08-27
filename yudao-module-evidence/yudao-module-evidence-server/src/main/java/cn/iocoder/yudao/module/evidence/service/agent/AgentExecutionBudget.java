package cn.iocoder.yudao.module.evidence.service.agent;

public record AgentExecutionBudget(int maxSteps, int maxLlmCalls, long maxElapsedMs) {
    public AgentExecutionBudget {
        if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
        if (maxLlmCalls <= 0) throw new IllegalArgumentException("maxLlmCalls must be > 0");
        if (maxElapsedMs <= 0) throw new IllegalArgumentException("maxElapsedMs must be > 0");
    }

    public static AgentExecutionBudget defaults() {
        // Planner + Tool Runtime + Goal Evaluator share this wall-clock budget.
        // 15s is below the observed cost of one normal planner call plus one semantic retrieval,
        // leaving no room for goal evaluation even when the execution plan is minimal.
        return new AgentExecutionBudget(6, 6, 30_000L);
    }
}
