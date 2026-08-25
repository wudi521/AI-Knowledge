package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A factual Runtime reference produced by one plan node.
 *
 * <p>candidateEntityIds 与 verifiedEntityIds 严格分离：前者可用于 DAG 候选集合组合，
 * 后者才允许进入 trusted scope。EMPTY 也可以形成 Reference，用于保留“查询为空”的事实来源。</p>
 */
public record ReferenceRecord(String referenceId,
                              String planId,
                              String nodeId,
                              String capability,
                              CapabilityResultStatus status,
                              String summary,
                              String deterministicAnswer,
                              List<Evidence> evidences,
                              List<Long> candidateEntityIds,
                              List<Long> verifiedEntityIds,
                              Map<String, Object> metadata) {
    public ReferenceRecord {
        evidences = immutable(evidences);
        candidateEntityIds = immutable(candidateEntityIds);
        verifiedEntityIds = immutable(verifiedEntityIds);
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 兼容迁移期旧调用：旧的 entityIds 语义保持 verified，不偷偷降级或升级。 */
    public ReferenceRecord(String referenceId,
                           String planId,
                           String nodeId,
                           String capability,
                           CapabilityResultStatus status,
                           String summary,
                           String deterministicAnswer,
                           List<Evidence> evidences,
                           List<Long> verifiedEntityIds,
                           Map<String, Object> metadata) {
        this(referenceId, planId, nodeId, capability, status, summary, deterministicAnswer,
                evidences, List.of(), verifiedEntityIds, metadata);
    }

    private static List<Long> immutable(List<Long> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        for (Long value : source) if (value != null && !out.contains(value)) out.add(value);
        return Collections.unmodifiableList(out);
    }

    private static List<Evidence> immutable(List<Evidence> source) {
        return source == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
