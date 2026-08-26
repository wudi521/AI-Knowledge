package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 结构化事实的数据粒度。
 *
 * <p>LOGICAL_ENTITY 表示按领域业务身份去重后的逻辑实体；SOURCE_RECORD 表示知识库中实际存在的物理记录。
 * 两者不能被 Planner 当成同一种集合直接做分组/计数结论，必须由 Metric/Executor 明确声明。</p>
 */
public enum DataGrain {
    LOGICAL_ENTITY,
    SOURCE_RECORD
}
