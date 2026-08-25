package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityFailureType;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete result of executing one validated AgentExecutionPlan. */
public record AgentRuntimeResult(CapabilityResultStatus status,
                                 CapabilityFailureType failureType,
                                 String message,
                                 Map<String, CapabilityResult> nodeResults,
                                 List<ActivityRecord> activities,
                                 List<ReferenceRecord> references,
                                 List<ProvenanceRecord> provenance) {
    public AgentRuntimeResult {
        nodeResults = nodeResults == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(nodeResults));
        activities = activities == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(activities));
        references = references == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(references));
        provenance = provenance == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(provenance));
    }

    public boolean failed() {
        return status == CapabilityResultStatus.FAILED;
    }
}
