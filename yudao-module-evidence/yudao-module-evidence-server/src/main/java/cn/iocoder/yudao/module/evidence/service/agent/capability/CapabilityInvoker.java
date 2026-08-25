package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Planner 与所有业务能力之间的唯一入口。 */
@Component
public class CapabilityInvoker {
    private static final Set<String> PROTECTED_SCOPE_ARGUMENTS = Set.of(
            "tenantId", "tenant_id", "userId", "user_id", "kbId", "kb_id",
            "domainCode", "domain_code", "traceId", "trace_id"
    );

    private final CapabilityRegistry registry;

    public CapabilityInvoker(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public PreparedCall prepare(String capabilityName, Map<String, Object> arguments,
                                CapabilityInvocationContext context) {
        KnowledgeCapability capability = registry.getVisible(capabilityName, context);
        if (capability == null) {
            return PreparedCall.rejected(AgentStopReason.CAPABILITY_UNAVAILABLE,
                    "capability unavailable in current context: " + capabilityName);
        }
        Map<String, Object> safeArguments = arguments == null ? Collections.emptyMap() : new LinkedHashMap<>(arguments);
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

    public CapabilityResult invoke(PreparedCall call, CapabilityInvocationContext context) {
        if (!call.accepted()) return CapabilityResult.failure(call.stopReason(), call.message());
        return call.capability().execute(context, call.arguments());
    }

    private String fingerprint(String capabilityName, Map<String, Object> arguments,
                               CapabilityInvocationContext context) {
        Map<String, Object> sorted = new TreeMap<>(arguments);
        String raw = capabilityName + "|" + JSONUtil.toJsonStr(sorted)
                + "|tenant=" + (context == null ? null : context.tenantId())
                + "|user=" + (context == null ? null : context.userId())
                + "|kb=" + (context == null ? null : context.kbId())
                + "|domain=" + (context == null ? null : context.domainCode());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record PreparedCall(boolean accepted, KnowledgeCapability capability,
                               Map<String, Object> arguments, String fingerprint,
                               AgentStopReason stopReason, String message) {
        static PreparedCall accepted(KnowledgeCapability capability, Map<String, Object> arguments, String fingerprint) {
            return new PreparedCall(true, capability, arguments, fingerprint, null, null);
        }
        static PreparedCall rejected(AgentStopReason reason, String message) {
            return new PreparedCall(false, null, Collections.emptyMap(), null, reason, message);
        }
    }
}
