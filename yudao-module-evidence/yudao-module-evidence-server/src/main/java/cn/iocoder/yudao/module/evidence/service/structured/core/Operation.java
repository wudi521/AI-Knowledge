package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Structured Query 聚合运算(Platform Core 领域无关)。
 * <p>
 * 运算只作用于 Domain Pack 返回的完整结构化数据集, 禁止基于 TopK 召回计算全集结论。
 */
public enum Operation {

    COUNT,
    COUNT_DISTINCT,
    SUM,
    AVG,
    MIN,
    MAX,

    /** 无聚合(EXACT_LOOKUP / LIST / GROUP / TOP_N 等非聚合类型) */
    NONE

}
