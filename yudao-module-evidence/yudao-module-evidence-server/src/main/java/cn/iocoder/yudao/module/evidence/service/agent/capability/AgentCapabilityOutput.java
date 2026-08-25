package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.List;

/**
 * 能力输出进入 Agent 主循环的统一适配协议。
 * 新增能力只实现本接口，不需要修改 AgenticQueryEngine 的业务分支。
 */
public interface AgentCapabilityOutput {
    String summary();
    String progressHash();

    default List<Evidence> evidences() {
        return List.of();
    }

    /** 结构化/确定性能力可直接给出可审计答案；语义检索能力返回 null，由 AnswerPipeline 生成。 */
    default String deterministicAnswer() {
        return null;
    }
}
