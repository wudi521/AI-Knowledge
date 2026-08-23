package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * Structured Query 查询类型(Platform Core 领域无关)。
 * <p>
 * 对外统一收敛到 STRUCTURED_QUERY 路由, 具体类型由本枚举表达:
 * EXACT_LOOKUP / AGGREGATE / LIST / GROUP / SORT / TOP_N。
 */
public enum QueryType {

    /** 精确对象查询(编号/标识定位单一对象) */
    EXACT_LOOKUP,

    /** 聚合统计(COUNT/SUM/AVG/MIN/MAX/COUNT_DISTINCT) */
    AGGREGATE,

    /** 列举(完整列举范围内对象) */
    LIST,

    /** 分组(按维度/对象分组返回指标) */
    GROUP,

    /** 排序(按指标排序) */
    SORT,

    /** Top-N(按指标排序取前/后 N) */
    TOP_N

}
