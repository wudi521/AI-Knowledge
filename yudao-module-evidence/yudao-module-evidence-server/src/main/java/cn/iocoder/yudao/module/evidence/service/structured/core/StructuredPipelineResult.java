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
        // Executor 任一阶段只要发现用户请求依赖的投影/派生值缺失，就必须 fail-closed；
        // 后续如果正式支持 PARTIAL，应新增显式 coverage 状态，而不是删除这条保护后静默回答。
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
        }
    }
}
