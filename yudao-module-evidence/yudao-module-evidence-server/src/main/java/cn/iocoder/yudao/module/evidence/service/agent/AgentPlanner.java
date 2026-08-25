package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;

import java.util.List;

/** Agent 下一步决策器。 */
public interface AgentPlanner {
    AgentDecision decide(AgentExecutionState state,
                         CapabilityInvocationContext context,
                         List<AgentObservation> observations,
                         List<ChatTurnDTO> history);
}
