package cn.iocoder.yudao.module.evidence.service.planner;

/** Query Planner V2 一级查询类型。用户语言无限，但执行语义必须收敛到有限类型。 */
public enum QueryClass {
    RULE,
    STRUCTURED_QUERY,
    SEMANTIC_QUERY,
    COMPOSITE_QUERY,
    EVIDENCE_QUERY,
    CLARIFY,
    ABSTAIN
}
