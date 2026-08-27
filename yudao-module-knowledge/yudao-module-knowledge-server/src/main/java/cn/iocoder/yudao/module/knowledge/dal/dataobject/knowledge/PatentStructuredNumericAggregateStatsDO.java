package cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge;

import lombok.Data;

import java.math.BigDecimal;

/** PATENT 数值指标在完整逻辑实体集合上的权威聚合证明。 */
@Data
public class PatentStructuredNumericAggregateStatsDO {
    private Long sourceEntityCount;
    private Long missingValueCount;
    private Long conflictCount;
    private BigDecimal sumValue;
    private BigDecimal avgValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;
}
