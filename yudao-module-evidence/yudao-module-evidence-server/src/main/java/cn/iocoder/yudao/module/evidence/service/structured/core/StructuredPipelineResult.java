package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组合结构化查询的确定性结果。 */
public record StructuredPipelineResult(boolean success,
                                       String message,
                                       List<Row> rows,
                                       Double scalarValue,
                                       boolean completeDataset,
                                       boolean authoritativeEmpty,
                                       int sourceEntityCount,
                                       int missingValueCount,
                                       Map<String, Object> metadata) {
    public StructuredPipelineResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static StructuredPipelineResult failure(String message) {
        return new StructuredPipelineResult(false, message, List.of(), null, false, false, 0, 0, Map.of());
    }

    public record Row(Long entityId,
                      String entityName,
                      Map<String, String> fields,
                      Double value,
                      String groupKey) {
        public Row {
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }
}
