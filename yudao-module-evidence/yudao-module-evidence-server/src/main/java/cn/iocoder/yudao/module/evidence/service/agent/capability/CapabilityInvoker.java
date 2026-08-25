package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Planner 与所有业务能力之间的唯一入口。 */
@Slf4j
@Component
public class CapabilityInvoker {
    private static final Set<String> PROTECTED_SCOPE_ARGUMENTS = Set.of(
            "tenantId", "tenant_id", "userId", "user_id", "kbId", "kb_id",
            "domainCode", "domain_code", "traceId", "trace_id", "permissions", "environment"
    );

    private final CapabilityRegistry registry;
    private final ExecutorService executor;

    @Autowired
    public CapabilityInvoker(CapabilityRegistry registry, EvidenceProperties properties) {
        this.registry = registry;
        int threads = properties == null || properties.getAgent() == null
                ? 8 : Math.max(2, properties.getAgent().getCapabilityTimeoutThreads());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-capability-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    /** 单元测试/纯 Java 场景兼容构造。 */
    public CapabilityInvoker(CapabilityRegistry registry) {
        this(registry, new EvidenceProperties());
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
        long timeoutMs = call.capability().definition().timeoutMs();
        Future<CapabilityResult> future = executor.submit(() -> call.capability().execute(context, call.arguments()));
        try {
            CapabilityResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return enforceMaxRows(call.capability().definition(), result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return CapabilityResult.failure(AgentStopReason.TIME_BUDGET_EXCEEDED,
                    "capability timed out after " + timeoutMs + "ms: " + call.capability().definition().name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return CapabilityResult.failure(AgentStopReason.TIME_BUDGET_EXCEEDED,
                    "capability execution interrupted: " + call.capability().definition().name());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[agent-capability][execution failed capability={} error={}]",
                    call.capability().definition().name(), cause.getMessage());
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "capability execution failed: " + call.capability().definition().name());
        }
    }

    private CapabilityResult enforceMaxRows(CapabilityDefinition definition, CapabilityResult result) {
        if (result == null) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE, "capability returned null result");
        }
        if (!result.success()) return result;
        int outputCount = outputCount(result);
        if (outputCount > definition.maxRows()) {
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "capability output exceeds maxRows: " + outputCount + " > " + definition.maxRows());
        }
        return result;
    }

    private int outputCount(CapabilityResult result) {
        for (String key : new String[]{"outputCount", "rowCount", "evidenceCount", "pairCount"}) {
            Object value = result.metadata().get(key);
            if (value instanceof Number n) return Math.max(0, n.intValue());
            if (value != null) {
                try { return Math.max(0, Integer.parseInt(String.valueOf(value))); }
                catch (Exception ignore) { }
            }
        }
        if (result.data() instanceof java.util.Collection<?> collection) return collection.size();
        return 0;
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

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
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
