package cn.iocoder.yudao.module.knowledge.service.agent.capability;

import cn.iocoder.yudao.module.knowledge.service.agent.AgentStopReason;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planner 与业务能力之间的唯一调用入口。
 */
@Component
public class CapabilityInvoker {

    /** Planner 永远不能通过 arguments 覆盖的系统范围字段。 */
    private static final Set<String> PROTECTED_SCOPE_ARGUMENTS = Set.of(
            "tenantId", "tenant_id", "userId", "user_id", "kbId", "kb_id", "traceId", "trace_id"
    );

    private final CapabilityRegistry capabilityRegistry;

    public CapabilityInvoker(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    public PreparedCall prepare(String capabilityName,
                                Map<String, Object> arguments,
                                CapabilityInvocationContext context) {
        KnowledgeCapability capability = capabilityRegistry.get(capabilityName);
        if (capability == null) {
            return PreparedCall.rejected(AgentStopReason.CAPABILITY_UNAVAILABLE,
                    "capability not found: " + capabilityName);
        }
        Map<String, Object> safeArguments = arguments == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(arguments);
        for (String key : safeArguments.keySet()) {
            if (PROTECTED_SCOPE_ARGUMENTS.contains(key)) {
                return PreparedCall.rejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                        "planner must not provide protected scope argument: " + key);
            }
        }
        for (String required : capability.definition().requiredArguments()) {
            if (!safeArguments.containsKey(required) || safeArguments.get(required) == null) {
                return PreparedCall.rejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                        "missing required capability argument: " + required);
            }
        }
        String fingerprint = fingerprint(capabilityName, safeArguments, context);
        return PreparedCall.accepted(capability, Collections.unmodifiableMap(safeArguments), fingerprint);
    }

    public CapabilityResult invoke(PreparedCall preparedCall, CapabilityInvocationContext context) {
        if (!preparedCall.accepted()) {
            return CapabilityResult.failure(preparedCall.stopReason(), preparedCall.message());
        }
        return preparedCall.capability().execute(context, preparedCall.arguments());
    }

    private String fingerprint(String capabilityName,
                               Map<String, Object> arguments,
                               CapabilityInvocationContext context) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(arguments.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder raw = new StringBuilder(capabilityName).append('|');
        for (Map.Entry<String, Object> entry : entries) {
            raw.append(entry.getKey()).append('=').append(String.valueOf(entry.getValue())).append('&');
        }
        raw.append("|tenant=").append(context == null ? null : context.tenantId())
                .append("|user=").append(context == null ? null : context.userId())
                .append("|kb=").append(context == null ? null : context.kbId());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record PreparedCall(boolean accepted,
                               KnowledgeCapability capability,
                               Map<String, Object> arguments,
                               String fingerprint,
                               AgentStopReason stopReason,
                               String message) {

        static PreparedCall accepted(KnowledgeCapability capability,
                                     Map<String, Object> arguments,
                                     String fingerprint) {
            return new PreparedCall(true, capability, arguments, fingerprint, null, null);
        }

        static PreparedCall rejected(AgentStopReason stopReason, String message) {
            return new PreparedCall(false, null, Collections.emptyMap(), null, stopReason, message);
        }
    }
}
