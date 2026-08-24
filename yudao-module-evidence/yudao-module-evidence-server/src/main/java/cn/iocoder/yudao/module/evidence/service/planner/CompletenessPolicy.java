package cn.iocoder.yudao.module.evidence.service.planner;

/** 查询结果完整性要求。 */
public enum CompletenessPolicy {
    /** 必须基于完整逻辑数据集，禁止用 TopK 推断全集。 */
    COMPLETE_REQUIRED,
    /** 尽力召回，允许语义检索候选集。 */
    BEST_EFFORT,
    /** 用户明确只要求 TopN/候选。 */
    TOP_K_ALLOWED
}
