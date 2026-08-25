package cn.iocoder.yudao.module.evidence.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Planner 的结构化输出。 */
public record AgentDecision(AgentActionType action,
                            String capability,
                            Map<String, Object> arguments,
                            String purpose,
                            String message) {
    public AgentDecision {
        if (action == null) throw new IllegalArgumentException("action must not be null");
        arguments = arguments == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
