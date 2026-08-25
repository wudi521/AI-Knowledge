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
import java.util.Locale;
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
    /**
     * 系统范围和执行预算永远由服务端注入。这里使用去下划线/连字符并转小写后的规范名，
     * 防止 Planner 通过 tenant_id / TenantId / KB-ID 等变体绕过保护。
     */
    private static final Set<String> PROTECTED_SCOPE_ARGUMENTS = Set.of(
            "tenantid", "userid", "kbid", "domaincode", "traceid", "requestid",
            "permissions", "kbcapabilities", "contextentityids", "environment", "writeallowed",
            "timeoutms", "maxrows", "maxsteps", "maxllmcalls", "maxelapsedms"
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
        CapabilityDefinition definition = capability.definition();

        for (String key : safeArguments.keySet()) {
            if (key == null || key.isBlank()) {
                return PreparedCall.recoverableRejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                        "capability argument name must not be blank");
            }
            if (PROTECTED_SCOPE_ARGUMENTS.contains(normalizeArgumentName(key))) {
                // 系统 scope / 权限边界属于安全错误，禁止 Planner 通过反复试探绕过。
                return PreparedCall.rejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                        "planner must not provide protected scope argument: " + key);
            }
        }

        // 新版 capability 的 argumentSchema 同时是 Planner 契约与 Invoker 参数白名单。
        // 旧纵切兼容构造器 schema 为空时不做未知参数拦截，避免破坏历史测试/迁移能力。
        if (definition.argumentSchema() != null && !definition.argumentSchema().isEmpty()) {
            for (String key : safeArguments.keySet()) {
                if (!definition.argumentSchema().containsKey(key)) {
                    // 纯调用契约错误允许 Agent 在剩余预算内根据真实 schema 自修复。
                    return PreparedCall.recoverableRejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                            "unknown capability argument: " + key);
                }
            }
        }

        for (String required : definition.requiredArguments()) {
            if (!safeArguments.containsKey(required) || safeArguments.get(required) == null) {
                return PreparedCall.recoverableRejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                        "missing required capability argument: " + required);
            }
        }
        String fingerprint = fingerprint(capabilityName, safeArguments, context);
        return PreparedCall.accepted(capability, Collections.unmodifiableMap(safeArguments), fingerprint);
    }

    public CapabilityResult invoke(PreparedCall call, CapabilityInvocationContext context) {
        if (!call.accepted()) {
            return call.recoverable()
                    ? CapabilityResult.recoverableFailure(call.message(), Map.of("errorKind", "PREPARE_CONTRACT"))
                    : CapabilityResult.failure(call.stopReason(), call.message());
        }
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

    private String normalizeArgumentName(String key) {
        if (key == null) return "";
        return key.replace("_", "")
                .replace("-", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public record PreparedCall(boolean accepted, KnowledgeCapability capability,
                               Map<String, Object> arguments, String fingerprint,
                               AgentStopReason stopReason, String message,
                               boolean recoverable) {
        static PreparedCall accepted(KnowledgeCapability capability, Map<String, Object> arguments, String fingerprint) {
            return new PreparedCall(true, capability, arguments, fingerprint, null, null, false);
        }
        static PreparedCall rejected(AgentStopReason reason, String message) {
            return new PreparedCall(false, null, Collections.emptyMap(), null, reason, message, false);
        }
        static PreparedCall recoverableRejected(AgentStopReason reason, String message) {
            return new PreparedCall(false, null, Collections.emptyMap(), null, reason, message, true);
        }
    }
}
