package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * ExecutionMode(CQ-38): 查询执行模式。
 * <p>
 * - STRUCTURED: 完整结构化数据集确定性执行(COUNT/SUM/LIST/GROUP/TOP_N)。
 * - PER_ENTITY_SEMANTIC: 逐实体 SCOPED_RAG(有明确实体集, 但语义型属性无法结构化消解, 如"核心技术分别是什么")。
 * - CROSS_ENTITY_SEMANTIC: 跨实体语义比较(无历史实体集, 在当前 KB 内做语义检索/对比)。
 * <p>
 * 语义执行受 maxSemanticEntities 限制; 超限 → CLARIFY(禁止静默截断)。
 */
public enum ExecutionMode {

    STRUCTURED,
    PER_ENTITY_SEMANTIC,
    CROSS_ENTITY_SEMANTIC;

    public static final String CODE_STRUCTURED = "STRUCTURED";
    public static final String CODE_PER_ENTITY_SEMANTIC = "PER_ENTITY_SEMANTIC";
    public static final String CODE_CROSS_ENTITY_SEMANTIC = "CROSS_ENTITY_SEMANTIC";

    public String code() {
        return name();
    }
}
