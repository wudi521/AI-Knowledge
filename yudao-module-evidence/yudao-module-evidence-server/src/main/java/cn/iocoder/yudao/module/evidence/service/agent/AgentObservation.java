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
        Map<String, Object> meta = metadata == null ? Map.of() : metadata;
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
}
