package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.List;

/**
 * 能力输出进入公共 Runtime 的统一事实协议。
 * 新增能力只实现本接口，不需要在 Runtime 增加业务场景分支。
 */
public interface AgentCapabilityOutput {
    String summary();
    String progressHash();

    default List<Evidence> evidences() {
        return List.of();
    }

    /**
     * 候选实体可以参加 DAG 集合组合，但绝不能自动进入 trusted scope。
     *
     * <p>公共协议默认不从 Evidence.documentId 猜业务 entityId；Document 是知识载体，
     * 只有 Tool 通过对应 DomainEvidenceEntityMapper 明确完成映射后才能覆盖本方法。</p>
     */
    default List<Long> candidateEntityIds() {
        return List.of();
    }

    /**
     * 只有确定性/结构化能力确认过的实体才允许进入 trusted scope。
     * 普通语义检索候选必须保持默认空集合，防止 candidate feedback contamination。
     */
    default List<Long> verifiedEntityIds() {
        return List.of();
    }

    /** 结构化/确定性能力可直接给出可审计答案；语义检索能力返回 null，由 AnswerPipeline 生成。 */
    default String deterministicAnswer() {
        return null;
    }
}
