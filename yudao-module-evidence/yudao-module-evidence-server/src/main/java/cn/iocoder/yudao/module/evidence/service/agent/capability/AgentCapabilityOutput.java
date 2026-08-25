package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.LinkedHashSet;
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
     * 检索/全文搜索等候选集合。候选实体可以参加 DAG 集合组合，但绝不能自动进入 trusted scope。
     *
     * <p>默认从 Evidence.documentId 提取真实候选实体 ID，因此任何证据型检索 Tool 都天然可参与
     * entity_set_operation；非数字 documentId 会安全忽略，具体能力也可覆盖本方法。</p>
     */
    default List<Long> candidateEntityIds() {
        List<Evidence> source = evidences();
        if (source == null || source.isEmpty()) return List.of();
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Evidence evidence : source) {
            if (evidence == null || evidence.getDocumentId() == null) continue;
            try {
                long id = Long.parseLong(String.valueOf(evidence.getDocumentId()).trim());
                if (id > 0) out.add(id);
            } catch (Exception ignore) {
                // candidate 提取失败不能把非实体标识强行当作可信 ID。
            }
        }
        return List.copyOf(out);
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
