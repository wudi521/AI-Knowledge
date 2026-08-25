package cn.iocoder.yudao.module.knowledge.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planner 的结构化决策。
 */
public final class AgentDecision {

    private final AgentActionType action;
    private final String capability;
    private final Map<String, Object> arguments;
    private final String purpose;
    private final String message;

    public AgentDecision(AgentActionType action, String capability, Map<String, Object> arguments,
                         String purpose, String message) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        this.action = action;
        this.capability = capability;
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.purpose = purpose;
        this.message = message;
    }

    public AgentActionType getAction() {
        return action;
    }

    public String getCapability() {
        return capability;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getMessage() {
        return message;
    }

}
