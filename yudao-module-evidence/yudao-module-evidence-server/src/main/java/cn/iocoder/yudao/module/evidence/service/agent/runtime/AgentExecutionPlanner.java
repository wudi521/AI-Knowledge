package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionState;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;

import java.util.List;

/** Query Planning contract for producing a bounded execution DAG. */
public interface AgentExecutionPlanner {
    AgentPlanningDecision plan(AgentExecutionState state,
                               CapabilityInvocationContext context,
                               List<AgentObservation> observations,
                               List<ReferenceRecord> references,
                               List<ChatTurnDTO> history,
                               int replanAttempt,
                               int maxPlanNodes);
}
