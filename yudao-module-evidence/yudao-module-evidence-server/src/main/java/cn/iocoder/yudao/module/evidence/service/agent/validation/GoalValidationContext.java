package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;

import java.util.List;

/** Goal Validation Pipeline 的强类型输入。 */
public record GoalValidationContext(String originalGoal,
                                    List<AgentObservation> observations,
                                    List<String> deterministicAnswers,
                                    List<Evidence> evidences,
                                    CapabilityInvocationContext invocationContext) {
    public GoalValidationContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
        deterministicAnswers = deterministicAnswers == null ? List.of() : List.copyOf(deterministicAnswers);
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
    }
}
