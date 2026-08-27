package cn.iocoder.yudao.module.evidence.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给 Planner 的受控观察。originalGoal 永远不从 Observation 反写。
 * 关键执行事实使用机器字段表达，summary 只用于可读诊断。
 */
public record AgentObservation(String capability,
                               String purpose,
                               String summary,
                               String progressHash,
                               String status,
                               boolean completeDataset,
                               boolean authoritativeEmpty,
                               boolean recoverableError,
                               String errorCode,
                               Map<String, Object> metadata) {

    /** Runtime/Goal Validator 共享的覆盖范围合同字段。 */
    public static final String META_REQUIRED_COVERAGE = "requiredCoverage";
    public static final String META_COVERAGE_COMPLETE = "coverageComplete";
    public static final String META_SOURCE_TRUNCATED = "sourceTruncated";
    public static final String COVERAGE_COMPLETE = "COMPLETE";

    public AgentObservation {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 兼容第一纵切旧构造。 */
    public AgentObservation(String capability, String purpose, String summary, String progressHash) {
        this(capability, purpose, summary, progressHash,
                "SUCCESS", false, false, false, null, Map.of());
    }

    public static AgentObservation success(String capability, String purpose, String summary, String progressHash,
                                           Map<String, Object> metadata) {
        Map<String, Object> meta = normalizeMetadata(capability, metadata);
        return new AgentObservation(capability, purpose, summary, progressHash, "SUCCESS",
                Boolean.TRUE.equals(meta.get("completeDataset")),
                Boolean.TRUE.equals(meta.get("authoritativeEmpty")),
                false, null, meta);
    }

    public static AgentObservation recoverableError(String capability, String purpose, String summary,
                                                    String progressHash, AgentStopReason reason,
                                                    Map<String, Object> metadata) {
        return new AgentObservation(capability, purpose, summary, progressHash, "ERROR",
                false, false, true, reason == null ? null : reason.name(), metadata);
    }

    /**
     * structured_query 的“计算范围完整”与“最终输出是否被 limit”是两个正交事实。
     *
     * <p>当前 structured_query 的产品合同是：所有结构化结果都基于当前授权范围内的完整集合计算，
     * limit 只裁剪最终输出。因此 Runtime 在 Observation 边界强制声明 requiredCoverage=COMPLETE，
     * 不能由 Planner/Capability 通过自然语言或自报 metadata 降级。Goal Validator 会再次校验执行证明。</p>
     *
     * <p>历史字段 outputComplete/limited 只描述最终输出行，不能再被 Planner/Evaluator 当成
     * 数据源覆盖证明。这里统一补齐 coverage contract，保持旧字段兼容。</p>
     */
    private static Map<String, Object> normalizeMetadata(String capability, Map<String, Object> metadata) {
        Map<String, Object> normalized = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (!"structured_query".equals(capability)) return normalized;

        // 结构化执行当前必须建立在完整数据集之上；调用方不能把它降级为 PARTIAL/BOUNDED。
        normalized.put(META_REQUIRED_COVERAGE, COVERAGE_COMPLETE);
        normalized.put("coverageContractVersion", 1);

        boolean completeDataset = Boolean.TRUE.equals(normalized.get("completeDataset"));
        boolean sourceTruncated = Boolean.TRUE.equals(normalized.get(META_SOURCE_TRUNCATED));
        int missingValueCount = number(normalized.get("missingValueCount"), 0);
        boolean coverageComplete = completeDataset && !sourceTruncated && missingValueCount == 0;
        boolean outputLimited = Boolean.TRUE.equals(normalized.get("limited"));

        normalized.put(META_SOURCE_TRUNCATED, sourceTruncated);
        normalized.put(META_COVERAGE_COMPLETE, coverageComplete);
        normalized.put("outputLimited", outputLimited);

        Object sourceRows = firstNumber(normalized.get("sourceRowCount"), normalized.get("sourceEntityCount"));
        if (sourceRows != null) normalized.put("rowsConsidered", sourceRows);

        Object matchedRows = firstNumber(normalized.get("fullOutputCount"));
        if (matchedRows != null) normalized.put("matchedRowCount", matchedRows);

        if (outputLimited) {
            Object outputCount = firstNumber(normalized.get("outputCount"));
            if (outputCount != null) normalized.put("resultLimit", outputCount);
        }
        return normalized;
    }

    private static Object firstNumber(Object... values) {
        if (values == null) return null;
        for (Object value : values) if (value instanceof Number) return value;
        return null;
    }

    private static int number(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
