package cn.iocoder.yudao.module.evidence.service.agent;

/** Planner 允许输出的有限机器动作；固定的是机器协议，不是用户语义分类。 */
public enum AgentActionType {
    CALL_CAPABILITY,
    ANSWER,
    NEED_MORE_INFO,
    STOP
}
