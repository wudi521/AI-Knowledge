package cn.iocoder.yudao.module.evidence.service.agent;

/** 给 Planner 的受控观察摘要；不允许反写 originalGoal。 */
public record AgentObservation(String capability,
                               String purpose,
                               String summary,
                               String progressHash) {
}
