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

        // V1.1 当前产品策略不允许把 PARTIAL 伪装成 FULL。
        if (success && missingValueCount > 0) {
            success = false;
            completeDataset = false;
            authoritativeEmpty = false;
            message = "structured result is incomplete: " + missingValueCount
                    + " required value(s) are missing; refusing to present PARTIAL data as FULL";
        }
    }

    public static StructuredPipelineResult failure(String message) {
        String normalized = message;
        if (normalized != null && normalized.startsWith("filter literal is not valid for ")) {
            normalized = "invalid filter literal for "
                    + normalized.substring("filter literal is not valid for ".length());
        }
        return new StructuredPipelineResult(false, normalized, List.of(), null, false, false, 0, 0, Map.of());
    }

    public record Row(Long entityId,
                      String entityName,
                      Map<String, String> fields,
                      Double value,
                      String groupKey) {
        public Row {
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            // EXPLODE 后的行表达的是字段元素/派生值，而不是一组可供后续代词引用的实体。
            // 因此不能携带实体 ID 进入 Agent trusted scope。
            if (fields.keySet().stream().anyMatch(key -> key != null && key.contains("|EXPLODE"))) {
                entityId = null;
            }
        }
    }
}
