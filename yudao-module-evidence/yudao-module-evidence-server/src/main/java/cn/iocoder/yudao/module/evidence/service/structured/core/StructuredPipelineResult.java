package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.ArrayList;
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
        Map<String, Object> safeMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);

        // V1.1 当前产品策略不允许把 PARTIAL 伪装成 FULL。
        if (success && missingValueCount > 0) {
            success = false;
            completeDataset = false;
            authoritativeEmpty = false;
            safeMetadata.put("completeDataset", false);
            safeMetadata.put("outputComplete", false);
            safeMetadata.put("missingValueCount", missingValueCount);
            message = "structured result is incomplete: " + missingValueCount
                    + " required value(s) are missing; refusing to present PARTIAL data as FULL"
                    + diagnosticSuffix(safeMetadata);
        }
        metadata = Collections.unmodifiableMap(safeMetadata);
    }

    public static StructuredPipelineResult failure(String message) {
        return failure(message, Map.of());
    }

    public static StructuredPipelineResult failure(String message, Map<String, Object> metadata) {
        String normalized = message;
        if (normalized != null && normalized.startsWith("filter literal is not valid for ")) {
            normalized = "invalid filter literal for "
                    + normalized.substring("filter literal is not valid for ".length());
        }
        Map<String, Object> safe = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        safe.put("completeDataset", false);
        safe.put("outputComplete", false);
        normalized = normalized + diagnosticSuffix(safe);
        return new StructuredPipelineResult(false, normalized, List.of(), null, false, false, 0, 0, safe);
    }

    @SuppressWarnings("unchecked")
    private static String diagnosticSuffix(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("missingDiagnostics");
        if (!(raw instanceof Iterable<?> iterable)) return "";
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> sampleEntityIds = new ArrayList<>();
        int seen = 0;
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String kind = map.get("failureKind") == null ? "UNKNOWN" : String.valueOf(map.get("failureKind"));
            counts.merge(kind, 1, Integer::sum);
            Object entityId = map.get("entityId");
            if (entityId != null && sampleEntityIds.size() < 6) sampleEntityIds.add(String.valueOf(entityId));
            if (++seen >= 12) break;
        }
        if (counts.isEmpty()) return "";
        return "; missingBreakdown=" + counts
                + (sampleEntityIds.isEmpty() ? "" : "; sampleEntityIds=" + sampleEntityIds);
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
