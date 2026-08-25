package cn.iocoder.yudao.module.knowledge.service.agent;

/**
 * Agent 规划器允许输出的有限机器动作。
 *
 * <p>注意：这里固定的是机器协议，不是用户语义分类。</p>
 */
public enum AgentActionType {

    /** 调用一个受控能力。 */
    CALL_CAPABILITY,
    /** 当前证据已经足够回答原始目标。 */
    ANSWER,
    /** 缺少必须由用户补充的信息。 */
    NEED_MORE_INFO,
    /** 当前条件下无法继续。 */
    STOP

}
