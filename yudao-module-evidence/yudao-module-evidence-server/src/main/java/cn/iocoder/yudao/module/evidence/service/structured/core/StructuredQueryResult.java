package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured Query 结果(Platform Core 领域无关)。 */
@Data
@Builder
public class StructuredQueryResult {

    private String metricCode;
    private Operation operation;
    private Double value;
    private List<Row> rows;
    private Integer rowCount;
    private Integer totalEntities;
    private Integer validValueCount;
    private Integer missingValueCount;
    private Boolean conflict;
    /** 同一逻辑实体的重复物理记录在这些字段上出现不一致。 */
    @Builder.Default
    private Set<String> conflictFields = Collections.emptySet();
    private Boolean hasMore;
    private boolean truncated;
    private boolean unsupported;
    private String unsupportedReason;

    @Data
    @Builder
    public static class Row {
        private Long entityId;
        private String entityKey;
        private String entityName;
        private Double value;

        /**
         * 多字段投影值，key 为 DomainFieldRegistry fieldCode，value 为确定性结构化值。
         * LinkedHashMap 用于保持 Planner 投影顺序，便于确定性渲染。
         */
        @Builder.Default
        private Map<String, String> fields = new LinkedHashMap<>();
    }

    public static StructuredQueryResult unsupported(String reason) {
        return StructuredQueryResult.builder().unsupported(true).unsupportedReason(reason).build();
    }
}
