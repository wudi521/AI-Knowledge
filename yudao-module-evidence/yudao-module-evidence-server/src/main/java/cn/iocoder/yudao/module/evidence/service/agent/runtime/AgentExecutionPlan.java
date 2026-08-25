package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable execution DAG produced by Query Planning. */
public record AgentExecutionPlan(String planId,
                                 String originalGoal,
                                 int replanAttempt,
                                 List<PlanNode> nodes) {
    public AgentExecutionPlan {
        nodes = nodes == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    public static AgentExecutionPlan single(String planId,
                                            String originalGoal,
                                            String capability,
                                            java.util.Map<String, Object> arguments,
                                            String purpose) {
        return new AgentExecutionPlan(planId, originalGoal, 0,
                List.of(PlanNode.of("node-1", capability, arguments, purpose)));
    }
}
