package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** 把通用 Retrieval Plugin Pipeline 包装成一个 Agent 语义证据能力。 */
@Component
public class KnowledgeRetrievalCapability implements KnowledgeCapability {
    public static final String NAME = "knowledge_retrieval";
    private static final int MAX_SUBQUERIES = 4;
    private static final int MAX_MERGED_EVIDENCE = 20;

    private final PlannedEvidenceRetriever retriever;
    private final DomainEvidenceEntityMapperRegistry entityMapperRegistry;
    private final ExecutorService subqueryExecutor;

    @Autowired
    public KnowledgeRetrievalCapability(PlannedEvidenceRetriever retriever,
                                        DomainEvidenceEntityMapperRegistry entityMapperRegistry) {
        this.retriever = retriever;
        this.entityMapperRegistry = entityMapperRegistry;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-retrieval-subquery-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.subqueryExecutor = Executors.newFixedThreadPool(MAX_SUBQUERIES, factory);
    }

    /** 单测/迁移期兼容构造：没有 Domain mapper 时只返回 Evidence，不制造 candidate entity。 */
    public KnowledgeRetrievalCapability(PlannedEvidenceRetriever retriever) {
        this(retriever, new DomainEvidenceEntityMapperRegistry(List.of()));
    }

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "5",
                "在当前已授权知识库中检索语义证据；内部执行当前领域已注册的 Scope/Recall/Fusion/Rerank Pipeline。复杂问题可提供 focused subqueries，系统并行检索后合并证据；若当前 Domain 显式注册 Evidence->Entity 映射，还会按证据排名输出 candidateEntityIds，但绝不会输出 verifiedEntityIds。topK 控制召回证据深度，candidateTopN 独立控制向下游暴露的候选实体宽度；显式 candidateTopN 时，下游 proof Evidence 只保留所选候选实体对应的证据。",
                Map.of(
                        "query", "必填。当前要补足的信息需求；不得从候选中发明新的硬事实。",
                        "subqueries", "可选。最多 4 个相互独立、共同覆盖当前信息需求的 focused 子查询。非空时系统并行检索这些子查询并合并结果。",
                        "variants", "可选。最多 5 个保持同一信息需求不变的同义检索表达；仅单查询模式使用。",
                        "topK", "可选。每个检索请求返回的召回 Evidence 深度 1~20，默认 8；最终内部合并证据最多 20 条。",
                        "candidateTopN", "可选。1~20，默认 20。限制按最终 Evidence 排名去重后的 candidateEntityIds 数量。显式提供时，未入选候选实体的 Evidence 只保留计数/活动诊断，不进入下游 Reference proof scope；同一入选候选命中的多个 chunk 仍保留。单对象解析通常设为 1。",
                        "scope", "可选。CURRENT_KB 或 CONTEXT；只有用户明确指代上一轮已验证对象时才用 CONTEXT。"
                ), Set.of("query"), "EVIDENCE_LIST_WITH_CANDIDATES_AND_ACTIVITY", true,
                Set.of(), Set.of(), Set.of(), 8_000L, MAX_MERGED_EVIDENCE);
    }

    @Override
    public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                           Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("query") instanceof String query) || StrUtil.isBlank(query)) {
            return CapabilityArgumentValidation.invalid("query must be a non-blank string");
        }
        if (arguments.get("subqueries") != null && !stringArray(arguments.get("subqueries"), MAX_SUBQUERIES)) {
            return CapabilityArgumentValidation.invalid("subqueries must be an array of at most 4 non-blank strings");
        }
        if (arguments.get("variants") != null && !stringArray(arguments.get("variants"), 5)) {
            return CapabilityArgumentValidation.invalid("variants must be an array of at most 5 non-blank strings");
        }
        String topKError = boundedIntegerError(arguments.get("topK"), "topK");
        if (topKError != null) return CapabilityArgumentValidation.invalid(topKError);
        String candidateTopNError = boundedIntegerError(arguments.get("candidateTopN"), "candidateTopN");
        if (candidateTopNError != null) return CapabilityArgumentValidation.invalid(candidateTopNError);
        if (arguments.get("scope") != null) {
            String scope = String.valueOf(arguments.get("scope")).trim().toUpperCase(Locale.ROOT);
            if (!"CURRENT_KB".equals(scope) && !"CONTEXT".equals(scope)) {
                return CapabilityArgumentValidation.invalid("scope must be CURRENT_KB or CONTEXT");
            }
        }
        return CapabilityArgumentValidation.ok();
    }

    @Override
    public String canonicalExecutionKey(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (arguments == null) return null;
        List<String> subqueries = strings(arguments.get("subqueries"), MAX_SUBQUERIES);
        List<String> effectiveQueries = subqueries.isEmpty()
                ? List.of(normalizeQuery(arguments.get("query")))
                : subqueries.stream().map(this::normalizeQuery).filter(StrUtil::isNotBlank).distinct().sorted().toList();
        List<String> variants = subqueries.isEmpty()
                ? strings(arguments.get("variants"), 5).stream().map(this::normalizeQuery).distinct().sorted().toList()
                : List.of();
        String scope = String.valueOf(arguments.getOrDefault("scope", "CURRENT_KB")).trim().toUpperCase(Locale.ROOT);
        int topK = clampTopK(arguments.get("topK"));
        int candidateTopN = clampCandidateTopN(arguments.get("candidateTopN"));
        return "queries=" + effectiveQueries + ";variants=" + variants + ";scope=" + scope
                + ";topK=" + topK + ";candidateTopN=" + candidateTopN
                + ";candidateScopeExplicit=" + arguments.containsKey("candidateTopN");
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        if (context == null || context.kbId() == null || context.userId() == null) {
            return CapabilityResult.failure(AgentStopReason.PERMISSION_DENIED, "knowledge scope is incomplete");
        }
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (StrUtil.isBlank(query)) {
            return CapabilityResult.failure(AgentStopReason.INVALID_CAPABILITY_CALL, "query must not be blank");
        }
        List<String> variants = strings(arguments.get("variants"), 5);
        List<String> subqueries = strings(arguments.get("subqueries"), MAX_SUBQUERIES);
        int topK = clampTopK(arguments.get("topK"));
        int candidateTopN = clampCandidateTopN(arguments.get("candidateTopN"));
        boolean candidateScopeExplicit = arguments.containsKey("candidateTopN");
        List<Long> documentIds = scope(arguments.get("scope"), context);
        if (documentIds == null) {
            return CapabilityResult.failure(AgentStopReason.NEED_USER_INPUT,
                    "conversation scope was requested but no verified context entity set exists");
        }

        List<String> effectiveQueries = subqueries.isEmpty() ? List.of(query) : subqueries;
        List<QueryRun> runs = runQueries(effectiveQueries, subqueries.isEmpty() ? variants : List.of(),
                documentIds, topK, context);

        List<QueryRun> failed = runs.stream().filter(run -> run.result().failed()).toList();
        List<Map<String, Object>> activity = activity(runs);
        if (!failed.isEmpty()) {
            String failedQueries = failed.stream().map(QueryRun::query).collect(Collectors.joining(" | "));
            return CapabilityResult.failure(AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "one or more required retrieval subqueries failed: " + StrUtil.maxLength(failedQueries, 300),
                    Map.of("errorKind", "RETRIEVAL_SOURCE_FAILURE", "activity", activity,
                            "failedSubqueryCount", failed.size(), "subqueryCount", runs.size()));
        }

        List<Evidence> retrievedEvidences = mergeRoundRobin(runs, MAX_MERGED_EVIDENCE);
        List<Long> rankedCandidateEntityIds = entityMapperRegistry.candidateEntityIds(
                context.domainCode(), retrievedEvidences);
        List<Long> candidateEntityIds = rankedCandidateEntityIds.stream().limit(candidateTopN).toList();
        List<Evidence> proofEvidences = candidateScopeExplicit
                ? evidenceForCandidates(context.domainCode(), retrievedEvidences, candidateEntityIds)
                : retrievedEvidences;
        int matchedQueries = (int) runs.stream().filter(run -> !run.result().evidences().isEmpty()).count();
        int blockedQueries = (int) runs.stream().filter(run -> run.result().blocked()).count();
        String outcome;
        if (retrievedEvidences.isEmpty()) {
            outcome = blockedQueries > 0 ? "SCOPE_BLOCKED" : "NO_MATCHES";
        } else {
            outcome = blockedQueries > 0 ? "PARTIAL_SCOPE_BLOCKED" : "MATCHES";
        }
        Map<String, Integer> perQueryCounts = new LinkedHashMap<>();
        for (QueryRun run : runs) perQueryCounts.put(run.query(), run.result().evidences().size());

        Output output = new Output(proofEvidences, candidateEntityIds, List.copyOf(effectiveQueries), outcome,
                Map.copyOf(perQueryCounts), summary(proofEvidences));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievedEvidenceCount", retrievedEvidences.size());
        metadata.put("evidenceCount", proofEvidences.size());
        metadata.put("discardedEvidenceCount", Math.max(0, retrievedEvidences.size() - proofEvidences.size()));
        metadata.put("candidateEvidenceScoped", candidateScopeExplicit
                && entityMapperRegistry.hasMapper(context.domainCode()) && !candidateEntityIds.isEmpty());
        metadata.put("rankedCandidateEntityCount", rankedCandidateEntityIds.size());
        metadata.put("candidateEntityCount", candidateEntityIds.size());
        metadata.put("candidateTopN", candidateTopN);
        metadata.put("candidateScopeExplicit", candidateScopeExplicit);
        metadata.put("candidateScopeLimited", rankedCandidateEntityIds.size() > candidateEntityIds.size());
        metadata.put("candidateEntityMapped", entityMapperRegistry.hasMapper(context.domainCode()));
        metadata.put("entityTrust", "CANDIDATE");
        metadata.put("retrievalOutcome", outcome);
        metadata.put("subqueryCount", runs.size());
        metadata.put("matchedSubqueryCount", matchedQueries);
        metadata.put("blockedSubqueryCount", blockedQueries);
        metadata.put("scopeBlocked", blockedQueries > 0);
        metadata.put("allSubqueriesMatched", matchedQueries == runs.size());
        metadata.put("activity", activity);
        metadata.put("completeDataset", false);
        metadata.put("authoritativeEmpty", false);
        // semantic top-K retrieval and scope blocks are execution facts, not exhaustive corpus absence proofs.
        metadata.put("outputComplete", false);
        return CapabilityResult.success(output, metadata);
    }

    private List<Evidence> evidenceForCandidates(String domainCode,
                                                 List<Evidence> evidences,
                                                 List<Long> candidateEntityIds) {
        if (evidences == null || evidences.isEmpty() || candidateEntityIds == null || candidateEntityIds.isEmpty()
                || !entityMapperRegistry.hasMapper(domainCode)) {
            return evidences == null ? List.of() : List.copyOf(evidences);
        }
        Set<Long> selected = new LinkedHashSet<>(candidateEntityIds);
        List<Evidence> out = new ArrayList<>();
        for (Evidence evidence : evidences) {
            List<Long> mapped = entityMapperRegistry.candidateEntityIds(domainCode, List.of(evidence));
            if (mapped.stream().anyMatch(selected::contains)) out.add(evidence);
        }
        return List.copyOf(out);
    }

    private List<QueryRun> runQueries(List<String> queries, List<String> variants,
                                      List<Long> documentIds, int topK,
                                      CapabilityInvocationContext context) {
        if (queries.size() <= 1) {
            String q = queries.get(0);
            String traceId = context.traceId();
            PlannedEvidenceRetriever.Result result = retriever.search(q, variants, List.of(context.kbId()),
                    documentIds.isEmpty() ? null : documentIds, topK,
                    context.tenantId(), context.userId(), context.domainCode(), traceId);
            return List.of(new QueryRun(q, traceId, result));
        }
        List<CompletableFuture<QueryRun>> futures = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            int index = i;
            String q = queries.get(i);
            String subTrace = childTrace(context.traceId(), index);
            futures.add(CompletableFuture.supplyAsync(() -> {
                PlannedEvidenceRetriever.Result result = retriever.search(q, List.of(), List.of(context.kbId()),
                        documentIds.isEmpty() ? null : documentIds, topK,
                        context.tenantId(), context.userId(), context.domainCode(), subTrace);
                return new QueryRun(q, subTrace, result);
            }, subqueryExecutor));
        }
        List<QueryRun> out = new ArrayList<>();
        for (CompletableFuture<QueryRun> future : futures) {
            try {
                out.add(future.join());
            } catch (Exception e) {
                out.add(new QueryRun("unknown", context.traceId(),
                        PlannedEvidenceRetriever.Result.failed("subquery execution failed")));
            }
        }
        return List.copyOf(out);
    }

    /** round-robin 合并，避免一个子查询的高分结果把其它子问题证据全部挤掉。 */
    private List<Evidence> mergeRoundRobin(List<QueryRun> runs, int maxRows) {
        LinkedHashMap<String, Evidence> unique = new LinkedHashMap<>();
        int rank = 0;
        boolean added;
        do {
            added = false;
            for (QueryRun run : runs) {
                List<Evidence> rows = run.result().evidences();
                if (rank >= rows.size()) continue;
                Evidence evidence = rows.get(rank);
                String key = evidenceKey(evidence);
                Evidence existing = unique.get(key);
                if (existing == null || score(evidence) > score(existing)) unique.put(key, evidence);
                added = true;
                if (unique.size() >= maxRows) break;
            }
            rank++;
        } while (added && unique.size() < maxRows);
        List<Evidence> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparingDouble(this::score).reversed());
        return List.copyOf(out.subList(0, Math.min(maxRows, out.size())));
    }

    private List<Map<String, Object>> activity(List<QueryRun> runs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (QueryRun run : runs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query", run.query());
            item.put("traceId", run.traceId());
            item.put("status", run.result().status().name());
            item.put("evidenceCount", run.result().evidences().size());
            item.put("totalHits", run.result().totalHits() == null ? -1L : run.result().totalHits());
            item.put("totalHitsExact", Boolean.TRUE.equals(run.result().totalHitsExact()));
            item.put("candidateTotalHits", run.result().candidateTotalHits() == null ? -1L : run.result().candidateTotalHits());
            if (StrUtil.isNotBlank(run.result().errorMessage())) {
                if (run.result().blocked()) item.put("blockReason", run.result().errorMessage());
                else item.put("error", run.result().errorMessage());
            }
            out.add(Map.copyOf(item));
        }
        return List.copyOf(out);
    }

    private List<Long> scope(Object raw, CapabilityInvocationContext context) {
        String scope = raw == null ? "CURRENT_KB" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!"CONTEXT".equals(scope)) return List.of();
        return context.contextEntityIds().isEmpty() ? null : context.contextEntityIds();
    }

    private String boundedIntegerError(Object raw, String name) {
        if (raw == null) return null;
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            return name + " must be an integer between 1 and 20";
        }
        long value = ((Number) raw).longValue();
        return value < 1 || value > 20 ? name + " must be an integer between 1 and 20" : null;
    }

    private int clampTopK(Object raw) {
        return clampBounded(raw, 8);
    }

    private int clampCandidateTopN(Object raw) {
        return clampBounded(raw, MAX_MERGED_EVIDENCE);
    }

    private int clampBounded(Object raw, int fallback) {
        int value = fallback;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) try { value = Integer.parseInt(String.valueOf(raw)); } catch (Exception ignore) { }
        return Math.max(1, Math.min(20, value));
    }

    private boolean stringArray(Object raw, int limit) {
        if (!(raw instanceof Iterable<?> iterable)) return false;
        int count = 0;
        for (Object value : iterable) {
            if (!(value instanceof String text) || StrUtil.isBlank(text)) return false;
            if (++count > limit) return false;
        }
        return true;
    }

    private List<String> strings(Object raw, int limit) {
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object value : iterable) {
            String text = value == null ? null : String.valueOf(value).trim();
            if (StrUtil.isNotBlank(text)) out.add(text);
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    private String normalizeQuery(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim().replaceAll("\\s+", " ");
    }

    private String childTrace(String parent, int index) {
        return StrUtil.blankToDefault(parent, "agent") + "-rq" + (index + 1);
    }

    private String evidenceKey(Evidence evidence) {
        if (evidence == null) return "null";
        if (evidence.getChunkId() != null) return "chunk:" + evidence.getChunkId();
        return "doc:" + evidence.getDocumentId() + ":" + StrUtil.nullToEmpty(evidence.getContent()).hashCode();
    }

    private double score(Evidence evidence) {
        return evidence == null || evidence.getScore() == null ? 0D : evidence.getScore();
    }

    private String summary(List<Evidence> evidences) {
        return evidences.stream().limit(10)
                .map(e -> "doc=" + e.getDocumentId() + ",name=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 80)
                        + ",score=" + e.getScore() + ",text=" + StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 180))
                .collect(Collectors.joining(" | "));
    }

    @PreDestroy
    public void shutdown() {
        subqueryExecutor.shutdownNow();
    }

    private record QueryRun(String query, String traceId, PlannedEvidenceRetriever.Result result) { }

    public record Output(List<Evidence> evidences,
                         List<Long> candidateEntityIds,
                         List<String> executedQueries,
                         String retrievalOutcome,
                         Map<String, Integer> perQueryCounts,
                         String summary) implements AgentCapabilityOutput {
        public Output {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            candidateEntityIds = candidateEntityIds == null ? List.of() : List.copyOf(candidateEntityIds);
            executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
            perQueryCounts = perQueryCounts == null ? Map.of() : Map.copyOf(perQueryCounts);
        }

        @Override
        public String progressHash() {
            String queryHash = Integer.toHexString(String.valueOf(executedQueries).hashCode());
            if (evidences.isEmpty()) return retrievalOutcome + ":" + queryHash;
            String chunks = evidences.stream().map(e -> String.valueOf(e.getChunkId())).collect(Collectors.joining(","));
            return retrievalOutcome + ":" + queryHash + ":" + chunks;
        }
    }
}
