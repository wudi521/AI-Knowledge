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
    /** Runtime 只对 TIMEOUT / THROTTLED / TRANSIENT 做有限原样重试。 */
    private static final int MAX_RUNTIME_RETRIES = 2;
    private static final long INITIAL_RETRY_BACKOFF_MS = 50L;

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

        // argumentSchema 是对 Planner 的可读契约，同时作为中央参数名白名单。
        if (definition.argumentSchema() != null && !definition.argumentSchema().isEmpty()) {
            for (String key : safeArguments.keySet()) {
                if (!definition.argumentSchema().containsKey(key)) {
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

        // 真正的机器参数契约：类型、范围、对象/数组形状由 capability 代码校验，
        // 不把正确性寄托在 Planner 是否“看懂” argumentSchema 的文字描述。
        CapabilityArgumentValidation validation;
        try {
            validation = capability.validateArguments(context, Collections.unmodifiableMap(safeArguments));
        } catch (RuntimeException e) {
            log.warn("[agent-capability][argument validation failed capability={} error={}]",
                    definition.name(), e.getMessage());
            return PreparedCall.recoverableRejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                    "capability argument validation failed");
        }
        if (validation != null && !validation.valid()) {
            return PreparedCall.recoverableRejected(AgentStopReason.INVALID_CAPABILITY_CALL,
                    validation.message() == null ? "capability arguments are invalid" : validation.message());
        }

        // 重复调用必须按“最终执行语义”识别，而不是按 LLM 原始 JSON 文本识别。
        String canonicalKey = null;
        try {
            canonicalKey = capability.canonicalExecutionKey(context, Collections.unmodifiableMap(safeArguments));
        } catch (RuntimeException e) {
            log.debug("[agent-capability][canonical key unavailable capability={} error={}]",
                    definition.name(), e.getMessage());
        }
        String fingerprint = fingerprint(capabilityName, canonicalKey, safeArguments, context);
        return PreparedCall.accepted(capability, Collections.unmodifiableMap(safeArguments), fingerprint);
    }

    /**
     * Runtime 执行入口。Planner 修正和 Runtime retry 在这里彻底分离：
     * plannerRecoverable 结果直接返回给上层重新规划；runtimeRetryable 才会在本层原样重试。
     */
    public CapabilityResult invoke(PreparedCall call, CapabilityInvocationContext context) {
        if (!call.accepted()) {
            return call.recoverable()
                    ? CapabilityResult.recoverableFailure(call.message(), Map.of("errorKind", "PREPARE_CONTRACT"))
                    : CapabilityResult.failure(call.stopReason(), call.message());
        }

        CapabilityResult last = null;
        for (int attempt = 0; attempt <= MAX_RUNTIME_RETRIES; attempt++) {
            last = invokeOnce(call, context);
            if (last == null || !last.runtimeRetryable() || attempt >= MAX_RUNTIME_RETRIES
                    || Thread.currentThread().isInterrupted()) {
                return enforceMaxRows(call.capability().definition(), last);
            }
            if (!backoff(attempt)) {
                return enforceMaxRows(call.capability().definition(), last);
            }
        }
        return enforceMaxRows(call.capability().definition(), last);
    }

    private CapabilityResult invokeOnce(PreparedCall call, CapabilityInvocationContext context) {
        long timeoutMs = call.capability().definition().timeoutMs();
        Future<CapabilityResult> future = executor.submit(() -> call.capability().execute(context, call.arguments()));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return CapabilityResult.failure(CapabilityFailureType.TIMEOUT, AgentStopReason.TIME_BUDGET_EXCEEDED,
                    "capability timed out after " + timeoutMs + "ms: " + call.capability().definition().name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            // 调用线程已经被中断，不能继续 backoff/retry。
            return CapabilityResult.failure(CapabilityFailureType.TIMEOUT, AgentStopReason.TIME_BUDGET_EXCEEDED,
                    "capability execution interrupted: " + call.capability().definition().name());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[agent-capability][execution failed capability={} error={}]",
                    call.capability().definition().name(), cause.getMessage());
            // 未经能力显式分类的异常不猜测为 transient，防止错误重试副作用操作。
            return CapabilityResult.failure(CapabilityFailureType.DEPENDENCY, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "capability execution failed: " + call.capability().definition().name());
        }
    }

    private boolean backoff(int completedRetryIndex) {
        long delay = INITIAL_RETRY_BACKOFF_MS * (1L << Math.min(completedRetryIndex, 6));
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private CapabilityResult enforceMaxRows(CapabilityDefinition definition, CapabilityResult result) {
        if (result == null) {
            return CapabilityResult.failure(CapabilityFailureType.DEPENDENCY, AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "capability returned null result");
        }
        if (!result.success()) return result;
        int outputCount = outputCount(result);
        if (outputCount > definition.maxRows()) {
            return CapabilityResult.failure(CapabilityFailureType.DATA_INCOMPLETE, AgentStopReason.NO_RELIABLE_EVIDENCE,
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

    private String fingerprint(String capabilityName, String canonicalKey, Map<String, Object> arguments,
                               CapabilityInvocationContext context) {
        Map<String, Object> sorted = new TreeMap<>(arguments);
        String executionMaterial = canonicalKey == null || canonicalKey.isBlank()
                ? "RAW:" + JSONUtil.toJsonStr(sorted)
                : "CANONICAL:" + canonicalKey;
        String raw = capabilityName + "|" + executionMaterial
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
