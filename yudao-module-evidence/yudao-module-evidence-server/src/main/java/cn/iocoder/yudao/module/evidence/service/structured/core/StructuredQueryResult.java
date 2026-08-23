package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Structured Query 结果(Platform Core 领域无关)。
 * <p>
 * rows 为范围内完整结构化数据集(非 TopK 召回); 聚合运算由 Executor 基于完整 rows 计算。
 * truncated=true 表示数据源未返回完整集(超过上限), 此时禁止基于 rows 计算全集结论。
 */
@Data
@Builder
public class StructuredQueryResult {

    /** 指标编码 */
    private String metricCode;

    /** 已执行的运算 */
    private Operation operation;

    /** 聚合结果值(COUNT/COUNT_DISTINCT/SUM/AVG/MIN/MAX; LIST/GROUP/TOP_N 时可为 null) */
    private Double value;

    /** 每对象一行(完整结构化数据集; 供 GROUP/LIST/TOP_N/分项展示) */
    private List<Row> rows;

    /** rows 行数 */
    private Integer rowCount;

    /** 数据是否被截断(数据源超过上限); true 时禁止全集聚合结论 */
    private boolean truncated;

    /** 是否数据源不支持该指标/运算 */
    private boolean unsupported;

    /** 不支持原因(unsupported=true 时) */
    private String unsupportedReason;

    @Data
    @Builder
    public static class Row {

        /** 对象编号(如 documentId) */
        private Long entityId;

        /** 对象键(如 publicationNo / applicationNo; 用于 COUNT_DISTINCT 去重) */
        private String entityKey;

        /** 对象名(如文档名/发明名称; 答案展示用) */
        private String entityName;

        /** 该对象指标值(如 claimCount) */
        private Double value;

    }

    public static StructuredQueryResult unsupported(String reason) {
        return StructuredQueryResult.builder().unsupported(true).unsupportedReason(reason).build();
    }
}
