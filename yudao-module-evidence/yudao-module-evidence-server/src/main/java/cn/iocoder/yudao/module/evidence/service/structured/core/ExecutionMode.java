package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 查询执行模式。P0 Query Planner V2 的执行层只认这些有限模式，禁止继续按自然语言补特例。
 */
public enum ExecutionMode {

    /** 完整结构化数据集确定性执行(COUNT/SUM/LIST/GROUP/TOP_N)。 */
    STRUCTURED,
    /** 单实体 hard-scope RAG。 */
    SCOPED_RAG,
    /** 对明确实体集逐实体 hard-scope RAG，再一次性综合。 */
    PER_ENTITY_SEMANTIC,
    /** 兼容旧名称：当前 KB 枚举实体后逐实体语义执行。 */
    CROSS_ENTITY_SEMANTIC,
    /** 跨实体比较/共同点/差异/相似性；要求至少两个不同实体的证据覆盖。 */
    CROSS_ENTITY_COMPARE,
    /** 普通全库混合检索，允许 TopK。 */
    HYBRID_RAG,
    /** 精确词/短语原文检索。 */
    EXACT_TEXT_SEARCH,
    /** 多步骤计划。 */
    COMPOSITE;

    public static final String CODE_STRUCTURED = "STRUCTURED";
    public static final String CODE_SCOPED_RAG = "SCOPED_RAG";
    public static final String CODE_PER_ENTITY_SEMANTIC = "PER_ENTITY_SEMANTIC";
    public static final String CODE_CROSS_ENTITY_SEMANTIC = "CROSS_ENTITY_SEMANTIC";
    public static final String CODE_CROSS_ENTITY_COMPARE = "CROSS_ENTITY_COMPARE";
    public static final String CODE_HYBRID_RAG = "HYBRID_RAG";
    public static final String CODE_EXACT_TEXT_SEARCH = "EXACT_TEXT_SEARCH";
    public static final String CODE_COMPOSITE = "COMPOSITE";

    public String code() {
        return name();
    }
}
