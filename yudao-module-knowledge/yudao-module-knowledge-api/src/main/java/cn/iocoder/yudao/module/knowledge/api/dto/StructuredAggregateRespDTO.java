package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

/** 权威结构化聚合结果；value 来自存储层完整集合计算，不是 TopK/rowCap 推断。 */
@Data
public class StructuredAggregateRespDTO {
    private String metricCode;
    private Long value;
    private Long sourceRowCount;
    private boolean completeDataset;
}
