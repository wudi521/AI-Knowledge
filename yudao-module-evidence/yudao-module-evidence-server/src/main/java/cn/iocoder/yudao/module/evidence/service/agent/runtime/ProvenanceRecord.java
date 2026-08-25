package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable provenance link from a ReferenceRecord back to plan/tool/scope. */
public record ProvenanceRecord(String referenceId,
                               String planId,
                               String nodeId,
                               String capability,
                               Long tenantId,
                               Long userId,
                               Long kbId,
                               String domainCode,
                               String traceId,
                               Map<String, Object> executionMetadata) {
    public ProvenanceRecord {
        executionMetadata = executionMetadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(executionMetadata));
    }
}
