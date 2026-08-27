package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityFailureType;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import com.alibaba.ttl.threadpool.TtlExecutors;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public Agentic Knowledge Runtime executor.
 *
 * <p>Responsibilities are deliberately generic: DAG validation, dependency scheduling, scope-safe
 * capability invocation, budget enforcement, bounded Runtime retry (delegated to CapabilityInvoker),
 * and Activity/Reference/Provenance recording. It contains no domain query-intent branch.</p>
 */
@Component
public class AgentRuntimeExecutor {
    private final CapabilityInvoker capabilityInvoker;
    private final ExecutorService nodeExecutor;
    private final AgentExecutionPlanValidator validator = new AgentExecutionPlanValidator();
    private final PlanArgumentResolver argumentResolver = new PlanArgumentResolver();

    @Autowired
    public AgentRuntimeExecutor(CapabilityInvoker capabilityInvoker, EvidenceProperties properties) {
        this.capabilityInvoker = capabilityInvoker;
        int threads = properties == null || properties.getAgent() == null
                ? 8 : Math.max(2, properties.getAgent().getRuntimeNodeThreads());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-runtime-node-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // DAG ready-node 并行执行会切换线程；必须传播 SecurityContext / tenant / trace 等 TTL 上下文。
        // 使用独立线程池而不是 commonPool，避免 Runtime 并行节点阻塞 JVM 全局 ForkJoinPool。
        this.nodeExecutor = TtlExecutors.getTtlExecutorService(Executors.newFixedThreadPool(threads, factory));
    }

    /** 单元测试/纯 Java 场景兼容构造。 */
    public AgentRuntimeExecutor(CapabilityInvoker capabilityInvoker) {
        this(capabilityInvoker, new EvidenceProperties());
    }

    public AgentRuntimeResult execute(AgentExecutionPlan plan,
                                      CapabilityInvocationContext context,
                                      AgentExecutionBudget budget) {
        AgentExecutionBudget safeBudget = budget == null ? AgentExecutionBudget.defaults() : budget;
        AgentExecutionPlanValidator.Validation validation = validator.validate(plan, safeBudget);
        if (!validation.valid()) {
            return new AgentRuntimeResult(CapabilityResultStatus.FAILED, CapabilityFailureType.VALIDATION,
                    validation.message(), Map.of(), List.of(), List.of(), List.of());
        }

        long runtimeStart = System.currentTimeMillis();
        Map<String, CapabilityResult> results = new LinkedHashMap<>();
        List<ActivityRecord> activities = new ArrayList<>();
        List<ReferenceRecord> references = new ArrayList<>();
        List<ProvenanceRecord> provenance = new ArrayList<>();

        while (results.size() < plan.nodes().size()) {
            if (System.currentTimeMillis() - runtimeStart >= safeBudget.maxElapsedMs()) {
                markRemainingTimeout(plan, results, activities);
                break;
            }

            List<PlanNode> ready = readyNodes(plan.nodes(), results.keySet());
            if (ready.isEmpty()) {
                return new AgentRuntimeResult(CapabilityResultStatus.FAILED, CapabilityFailureType.VALIDATION,
                        "no executable DAG node; dependency graph is inconsistent", results,
                        activities, references, provenance);
            }

            List<CompletableFuture<NodeExecution>> futures = new ArrayList<>();
            for (PlanNode node : ready) {
                futures.add(CompletableFuture.supplyAsync(() -> executeNode(
                        plan, node, context, new LinkedHashMap<>(results), runtimeStart, safeBudget), nodeExecutor));
            }

            for (CompletableFuture<NodeExecution> future : futures) {
                NodeExecution execution = future.join();
                results.put(execution.node().id(), execution.result());
                activities.add(execution.activity());
                if (execution.reference() != null) references.add(execution.reference());
                if (execution.provenance() != null) provenance.add(execution.provenance());
            }
        }

        CapabilityResultStatus overall = overallStatus(results.values());
        CapabilityFailureType failureType = overall == CapabilityResultStatus.FAILED
                ? firstFailureType(results.values()) : null;
        String message = overall == CapabilityResultStatus.FAILED
                ? "execution plan failed" : overall == CapabilityResultStatus.PARTIAL
                ? "execution plan completed partially" : null;
        return new AgentRuntimeResult(overall, failureType, message, results,
                activities, references, provenance);
    }

    private NodeExecution executeNode(AgentExecutionPlan plan,
                                      PlanNode node,
                                      CapabilityInvocationContext context,
                                      Map<String, CapabilityResult> completedResults,
                                      long runtimeStart,
                                      AgentExecutionBudget budget) {
        long start = System.currentTimeMillis();
        CapabilityResult dependencyFailure = dependencyFailure(node, completedResults);
        if (dependencyFailure != null) {
            return materialize(plan, node, context, start, dependencyFailure);
        }
        if (System.currentTimeMillis() - runtimeStart >= budget.maxElapsedMs()) {
            return materialize(plan, node, context, start,
                    CapabilityResult.failure(CapabilityFailureType.TIMEOUT,
                            AgentStopReason.TIME_BUDGET_EXCEEDED,
                            "runtime time budget exhausted before node execution"));
        }

        Map<String, Object> resolvedArguments;
        try {
            resolvedArguments = argumentResolver.resolve(node.arguments(), completedResults);
        } catch (IllegalArgumentException e) {
            return materialize(plan, node, context, start,
                    CapabilityResult.recoverableFailure(e.getMessage(), Map.of("errorKind", "PLAN_REFERENCE")));
        }

        CapabilityInvoker.PreparedCall prepared = capabilityInvoker.prepare(node.capability(), resolvedArguments, context);
        CapabilityResult result;
        if (!prepared.accepted()) {
            result = prepared.recoverable()
                    ? CapabilityResult.recoverableFailure(prepared.message(), Map.of("errorKind", "PREPARE_CONTRACT"))
                    : CapabilityResult.failure(prepared.stopReason(), prepared.message());
        } else {
            result = capabilityInvoker.invoke(prepared, context);
        }
        return materialize(plan, node, context, start, result);
    }

    private CapabilityResult dependencyFailure(PlanNode node, Map<String, CapabilityResult> completedResults) {
        for (String dependency : node.dependsOn()) {
            CapabilityResult result = completedResults.get(dependency);
            if (result == null) {
                return CapabilityResult.failure(CapabilityFailureType.DEPENDENCY,
                        AgentStopReason.NO_RELIABLE_EVIDENCE,
                        "dependency has not completed: " + dependency);
            }
            if (!result.success()) {
                return CapabilityResult.failure(CapabilityFailureType.DEPENDENCY,
                        AgentStopReason.NO_RELIABLE_EVIDENCE,
                        "dependency failed: " + dependency);
            }
        }
        return null;
    }

    private NodeExecution materialize(AgentExecutionPlan plan,
                                      PlanNode node,
                                      CapabilityInvocationContext context,
                                      long startedAt,
                                      CapabilityResult rawResult) {
        CapabilityResult result = rawResult == null
                ? CapabilityResult.failure(CapabilityFailureType.DEPENDENCY,
                    AgentStopReason.NO_RELIABLE_EVIDENCE, "capability returned null result")
                : rawResult;
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        ActivityRecord activity = new ActivityRecord(plan.planId(), node.id(), node.capability(),
                result.status(), result.failureType(), startedAt, elapsed, result.message(), result.metadata());

        ReferenceRecord reference = null;
        ProvenanceRecord provenance = null;
        if (result.success()) {
            String referenceId = StrUtil.blankToDefault(plan.planId(), "plan") + ":" + node.id();
            AgentCapabilityOutput output = result.data() instanceof AgentCapabilityOutput o ? o : null;
            List<Evidence> evidences = output == null || output.evidences() == null ? List.of() : output.evidences();
            List<Long> candidateIds = output == null || output.candidateEntityIds() == null
                    ? List.of() : output.candidateEntityIds().stream().filter(Objects::nonNull).distinct().toList();
            List<Long> verifiedIds = output == null || output.verifiedEntityIds() == null
                    ? List.of() : output.verifiedEntityIds().stream().filter(Objects::nonNull).distinct().toList();
            String summary = output == null
                    ? StrUtil.maxLength(String.valueOf(result.data()), 1200)
                    : StrUtil.maxLength(StrUtil.blankToDefault(output.summary(), String.valueOf(result.metadata())), 1200);
            String deterministicAnswer = output == null ? null : output.deterministicAnswer();
            reference = new ReferenceRecord(referenceId, plan.planId(), node.id(), node.capability(),
                    result.status(), summary, deterministicAnswer, evidences, candidateIds, verifiedIds, result.metadata());
            provenance = new ProvenanceRecord(referenceId, plan.planId(), node.id(), node.capability(),
                    context == null ? null : context.tenantId(),
                    context == null ? null : context.userId(),
                    context == null ? null : context.kbId(),
                    context == null ? null : context.domainCode(),
                    context == null ? null : context.traceId(), result.metadata());
        }
        return new NodeExecution(node, result, activity, reference, provenance);
    }

    private List<PlanNode> readyNodes(List<PlanNode> nodes, Set<String> completed) {
        List<PlanNode> ready = new ArrayList<>();
        for (PlanNode node : nodes) {
            if (completed.contains(node.id())) continue;
            if (completed.containsAll(node.dependsOn())) ready.add(node);
        }
        return ready;
    }

    private void markRemainingTimeout(AgentExecutionPlan plan,
                                      Map<String, CapabilityResult> results,
                                      List<ActivityRecord> activities) {
        for (PlanNode node : plan.nodes()) {
            if (results.containsKey(node.id())) continue;
            long now = System.currentTimeMillis();
            CapabilityResult timeout = CapabilityResult.failure(CapabilityFailureType.TIMEOUT,
                    AgentStopReason.TIME_BUDGET_EXCEEDED, "runtime time budget exhausted");
            results.put(node.id(), timeout);
            activities.add(new ActivityRecord(plan.planId(), node.id(), node.capability(), timeout.status(),
                    timeout.failureType(), now, 0L, timeout.message(), timeout.metadata()));
        }
    }

    private CapabilityResultStatus overallStatus(java.util.Collection<CapabilityResult> values) {
        if (values == null || values.isEmpty()) return CapabilityResultStatus.EMPTY;
        int failed = 0;
        boolean partial = false;
        boolean nonEmpty = false;
        for (CapabilityResult result : values) {
            if (result == null || result.status() == CapabilityResultStatus.FAILED) failed++;
            else if (result.status() == CapabilityResultStatus.PARTIAL) partial = true;
            else if (result.status() == CapabilityResultStatus.SUCCESS) nonEmpty = true;
        }
        if (failed == values.size()) return CapabilityResultStatus.FAILED;
        if (failed > 0 || partial) return CapabilityResultStatus.PARTIAL;
        return nonEmpty ? CapabilityResultStatus.SUCCESS : CapabilityResultStatus.EMPTY;
    }

    private CapabilityFailureType firstFailureType(java.util.Collection<CapabilityResult> values) {
        if (values == null) return null;
        for (CapabilityResult result : values) {
            if (result != null && result.failureType() != null) return result.failureType();
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        nodeExecutor.shutdownNow();
    }

    private record NodeExecution(PlanNode node,
                                 CapabilityResult result,
                                 ActivityRecord activity,
                                 ReferenceRecord reference,
                                 ProvenanceRecord provenance) {}
}
