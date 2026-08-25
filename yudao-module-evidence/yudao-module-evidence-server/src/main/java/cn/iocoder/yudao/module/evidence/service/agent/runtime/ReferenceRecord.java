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
 * EMPTY is also a reference when the underlying capability states authoritativeEmpty=true.
 */
public record ReferenceRecord(String referenceId,
                              String planId,
                              String nodeId,
                              String capability,
                              CapabilityResultStatus status,
                              String summary,
                              String deterministicAnswer,
                              List<Evidence> evidences,
                              List<Long> verifiedEntityIds,
                              Map<String, Object> metadata) {
    public ReferenceRecord {
        evidences = evidences == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(evidences));
        verifiedEntityIds = verifiedEntityIds == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(verifiedEntityIds));
        metadata = metadata == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
