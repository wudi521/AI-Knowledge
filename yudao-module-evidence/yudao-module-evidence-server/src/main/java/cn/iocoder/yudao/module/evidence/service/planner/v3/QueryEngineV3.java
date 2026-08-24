package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.evidence.service.semantics.DomainEntityIdentityProvider;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query Engine V3：自然语言只在 QueryPlannerV3 中理解一次。
 * 固定执行语义：Selection -> 有界检索(仅语义选择) -> EntitySet -> Action。
 */
@Component
public class QueryEngineV3 {

    private static final int SEMANTIC_ENTITY_LIMIT = 10;
    private static final int SELECTION_TOP_K = 12;
    private static final int EVIDENCE_TOP_K = 8;

    private final QueryPlannerV3 planner;
    private final QueryIntentValidatorV3 validator;
    private final RetrievalRefinementService refinementService;
    private final PlannedEvidenceRetriever plannedRetriever;
    private final StructuredQueryExecutor structuredExecutor;
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final List<DomainEntityIdentityProvider> identityProviders;
    private final KnowledgeApi knowledgeApi;
    private final AnswerPipeline answerPipeline;

    public QueryEngineV3(QueryPlannerV3 planner,
                         QueryIntentValidatorV3 validator,
                         RetrievalRefinementService refinementService,
                         PlannedEvidenceRetriever plannedRetriever,
                         StructuredQueryExecutor structuredExecutor,
                         DomainFieldRegistry fieldRegistry,
                         DomainMetricRegistry metricRegistry,
                         List<DomainEntityIdentityProvider> identityProviders,
                         KnowledgeApi knowledgeApi,
                         AnswerPipeline answerPipeline) {
        this.planner = planner;
        this.validator = validator;
        this.refinementService = refinementService;
        this.plannedRetriever = plannedRetriever;
        this.structuredExecutor = structuredExecutor;
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.identityProviders = identityProviders == null ? List.of() : identityProviders;
        this.knowledgeApi = knowledgeApi;
        this.answerPipeline = answerPipeline;
    }

    public Result execute(String query, List<Long> kbIds, String domainCode,
                          List<ChatTurnDTO> history, List<Long> explicitEntityIds,
                          Long tenantId, Long userId, String traceId) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        if (StrUtil.isBlank(query) || kbIds == null || kbIds.isEmpty() || userId == null) {
            return fail("INVALID_QUERY_CONTEXT", "查询上下文不完整。", null, stages);
        }
        stages.add(stage("QUERY_CONTEXT", 0,
                "query=“" + cut(query, 240) + "”; kbIds=" + kbIds + "; domain=" + domainCode,
                "historyTurns=" + size(history) + "; previousEntityIds=" + compactIds(explicitEntityIds)));

        QueryIntentV3 intent = planner.plan(query, domainCode, history, explicitEntityIds, traceId);
        stages.add(stage("SEMANTIC_PLAN", intent == null || intent.getPlannerElapsedMs() == null ? 0 : intent.getPlannerElapsedMs(),
                "自然语言 + Domain Schema + lexical facts + context", summarizeIntent(intent)));
        QueryIntentValidatorV3.Validation validation = validator.validate(intent);
        stages.add(stage("PLAN_VALIDATE", 0, "typed QueryIntentV3",
                validation.valid() ? "PASS" : "FAIL reason=" + validation.reasonCode()));
        if (!validation.valid()) return fail(validation.reasonCode(), "查询计划未通过白名单校验。", intent, stages);
        if (intent.isRequiresClarification()) {
            return clarify(StrUtil.blankToDefault(intent.getReasonCode(), "PLANNER_CLARIFY"),
                    StrUtil.blankToDefault(intent.getClarificationQuestion(), "请补充查询条件。"), intent, stages);
        }

        Long singleKbId = kbIds.size() == 1 ? kbIds.get(0) : null;
        if (singleKbId == null && needsSingleKb(intent)) {
            return clarify("MULTI_KB_STRUCTURED_UNSUPPORTED", "当前结构化选择/动作需要限定到一个知识库。", intent, stages);
        }

        SelectionResult selected = select(intent, kbIds, singleKbId, explicitEntityIds,
                tenantId, userId, traceId, stages);
        if (!selected.ok()) {
            return selected.clarification() != null
                    ? clarify(selected.reason(), selected.clarification(), intent, stages)
                    : fail(selected.reason(), selected.message(), intent, stages);
        }

        List<Long> normalized = normalizeDomainEntities(selected.entityIds(), domainCode);
        // 完整集合绝不做 TopK 截断；只限制语义 Best-Effort 的候选规模。
        List<Long> entityIds = intent.getSelection().getType() == QueryIntentV3.SelectionType.SEMANTIC
                ? normalized.stream().limit(SEMANTIC_ENTITY_LIMIT).toList() : normalized;
        stages.add(stage("ENTITY_SET", 0, "selection=" + intent.getSelection().getType(),
                "entityIds=" + compactIds(entityIds) + "; count=" + entityIds.size()
                        + "; guarantee=" + selected.guarantee()));

        return executeActions(intent, query, kbIds, singleKbId, entityIds, selected,
                tenantId, userId, history, traceId, stages);
    }

    private SelectionResult select(QueryIntentV3 intent, List<Long> kbIds, Long kbId,
                                   List<Long> contextIds, Long tenantId, Long userId,
                                   String traceId, List<QueryStageTimingDTO> stages) {
        QueryIntentV3.Selection selection = intent.getSelection();
        return switch (selection.getType()) {
            case CURRENT_SCOPE -> currentScope(kbIds, intent.getDomainCode(), stages);
            case RESULT_SET -> contextIds == null || contextIds.isEmpty()
                    ? SelectionResult.clarify("MISSING_RESULT_SET", "当前没有可复用的上一轮对象集合。")
                    : SelectionResult.ok(contextIds, List.of(), "CONTEXT_COMPLETE", null, null, null);
            case EXACT_ENTITY, STRUCTURED_FILTER -> structuredSelect(selection, intent, kbId, stages);
            case SEMANTIC -> semanticSelect(selection, intent.getDomainCode(), kbIds, tenantId, userId, traceId, stages);
            case EXACT_TEXT -> exactTextSelect(selection, intent.getDomainCode(), kbIds, tenantId, userId, traceId, stages);
        };
    }

    private SelectionResult currentScope(List<Long> kbIds, String domainCode, List<QueryStageTimingDTO> stages) {
        long start = System.currentTimeMillis();
        List<Long> ids = new ArrayList<>();
        try {
            for (Long kbId : kbIds) {
                List<Long> part = knowledgeApi.getPublishedDocumentIds(kbId).getCheckedData();
                if (part != null) ids.addAll(part);
            }
        } catch (Exception e) {
            return SelectionResult.fail("CURRENT_SCOPE_FAILED", "无法读取当前已发布对象范围。");
        }
        ids = normalizeDomainEntities(ids, domainCode);
        stages.add(stage("CURRENT_SCOPE_SELECT", System.currentTimeMillis() - start,
                "kbIds=" + kbIds + "; publishedOnly=true", "entityCount=" + ids.size()));
        return SelectionResult.ok(ids, List.of(), "STRUCTURED_COMPLETE", null, null, null);
    }

    private SelectionResult structuredSelect(QueryIntentV3.Selection selection, QueryIntentV3 intent,
                                               Long kbId, List<QueryStageTimingDTO> stages) {
        if (kbId == null) return SelectionResult.clarify("MULTI_KB_FILTER_UNSUPPORTED", "结构化过滤需要单知识库范围。");
        FieldDefinition field = fieldRegistry.byCode(intent.getDomainCode(), selection.getField()).orElse(null);
        if (field == null || !field.isFilterable()) return SelectionResult.fail("FILTER_FIELD_UNAVAILABLE", "过滤字段不可执行。");
        MetricDefinition metric = syntheticMetric(field, intent.getDomainCode());
        metricRegistry.register(metric);
        FilterOperator op;
        try {
            op = FilterOperator.valueOf(selection.getOperator().toUpperCase());
        } catch (Exception e) {
            return SelectionResult.fail("INVALID_FILTER_OPERATOR", "过滤运算符不可执行。");
        }
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.LIST)
                .domainCode(intent.getDomainCode()).entityType(field.getEntityType())
                .scope(QueryScope.currentKb(kbId)).metricCode(field.getFieldCode()).fieldCode(field.getFieldCode())
                .projections(List.of(field.getFieldCode())).operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .filterExpression(FilterExpression.condition(field.getFieldCode(), op, selection.getValues())).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        stages.add(stage("STRUCTURED_SELECT", System.currentTimeMillis() - start,
                "field=" + field.getFieldCode() + "; operator=" + op + "; values=" + selection.getValues(), resultSummary(result)));
        if (result == null || result.isUnsupported()) {
            return SelectionResult.fail("STRUCTURED_SELECTION_FAILED",
                    result == null ? "结构化选择无结果。" : result.getUnsupportedReason());
        }
        List<Long> ids = rows(result).stream().map(StructuredQueryResult.Row::getEntityId)
                .filter(Objects::nonNull).distinct().toList();
        return SelectionResult.ok(ids, List.of(), "STRUCTURED_COMPLETE", result, null, null);
    }

    /** 最多两轮：第一轮 -> Judge -> 可选第二轮。 */
    private SelectionResult semanticSelect(QueryIntentV3.Selection selection, String domainCode,
                                            List<Long> kbIds, Long tenantId, Long userId, String traceId,
                                            List<QueryStageTimingDTO> stages) {
        String selectionQuery = StrUtil.blankToDefault(selection.getQuery(), "");
        PlannedEvidenceRetriever.Result round1 = plannedRetriever.search(selectionQuery, selection.getQueryVariants(),
                kbIds, null, SELECTION_TOP_K, tenantId, userId, traceId);
        addRetrievalStages(stages, round1.analysis(), "I1_");
        List<Evidence> first = entityCandidates(round1.evidences(), domainCode);
        stages.add(stage("RETRIEVAL_ITERATION_1", retrievalTotal(round1.analysis()),
                "selectionQuery=“" + cut(selectionQuery, 220) + "”; variants=" + selection.getQueryVariants(),
                "entityCandidates=" + evidenceSummary(first)));

        RetrievalRefinementService.Decision decision = refinementService.decide(selectionQuery, first, traceId);
        stages.add(stage("RETRIEVAL_DECIDE", decision.elapsedMs(),
                "iteration=1; candidates=" + evidenceSummary(first),
                "decision=" + decision.type() + "; source=" + decision.source()
                        + "; reason=" + decision.reasonCode() + "; nextQueries=" + decision.nextQueries()
                        + "; selectedDocumentIds=" + decision.selectedDocumentIds()));
        if (decision.type() == RetrievalRefinementService.DecisionType.ABSTAIN) {
            return SelectionResult.fail(decision.reasonCode(), "当前检索结果不足以可靠确定目标对象。");
        }
        if (decision.type() == RetrievalRefinementService.DecisionType.ACCEPT) {
            List<Evidence> accepted = decision.selectedDocumentIds().isEmpty()
                    ? first : filterDocuments(first, decision.selectedDocumentIds());
            return fromEvidence(accepted, "SEMANTIC_BEST_EFFORT", round1.analysis(), round1.channels());
        }

        List<Long> hardScope = decision.type() == RetrievalRefinementService.DecisionType.NARROW
                ? decision.selectedDocumentIds() : null;
        List<String> next = decision.nextQueries();
        String round2Query = next.isEmpty() ? selectionQuery : next.get(0);
        List<String> round2Variants = next.size() <= 1 ? List.of() : next.subList(1, next.size());
        PlannedEvidenceRetriever.Result round2 = plannedRetriever.search(round2Query, round2Variants,
                kbIds, hardScope, SELECTION_TOP_K, tenantId, userId, traceId);
        addRetrievalStages(stages, round2.analysis(), "I2_");
        List<Evidence> second = entityCandidates(round2.evidences(), domainCode);
        stages.add(stage("RETRIEVAL_ITERATION_2", retrievalTotal(round2.analysis()),
                "decision=" + decision.type() + "; query=“" + cut(round2Query, 220)
                        + "”; variants=" + round2Variants + "; hardScope=" + compactIds(hardScope),
                "entityCandidates=" + evidenceSummary(second)));
        if (second.isEmpty() && !first.isEmpty()) {
            stages.add(stage("RETRIEVAL_FINAL_DECISION", 0, "iteration2 empty",
                    "fallback=iteration1; maximumIterations=2"));
            return fromEvidence(first, "SEMANTIC_BEST_EFFORT", round1.analysis(), round1.channels());
        }
        if (second.isEmpty()) return SelectionResult.fail("EMPTY_SEMANTIC_SELECTION", "没有检索到足够相关的对象。");
        stages.add(stage("RETRIEVAL_FINAL_DECISION", 0, "maximumIterations=2", "ACCEPT iteration2"));
        return fromEvidence(second, "SEMANTIC_BEST_EFFORT", round2.analysis(), round2.channels());
    }

    private SelectionResult exactTextSelect(QueryIntentV3.Selection selection, String domainCode,
                                             List<Long> kbIds, Long tenantId, Long userId, String traceId,
                                             List<QueryStageTimingDTO> stages) {
        PlannedEvidenceRetriever.Result result = plannedRetriever.exactText(selection.getQuery(), kbIds, null,
                20, tenantId, userId, traceId);
        addRetrievalStages(stages, result.analysis(), "EXACT_");
        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        boolean fullyReturned = Boolean.TRUE.equals(result.totalHitsExact()) && result.totalHits() != null
                && result.totalHits() <= evidences.size();
        return fromEvidence(entityCandidates(evidences, domainCode),
                fullyReturned ? "EXACT_ENTITY_COMPLETE" : "BEST_EFFORT", result.analysis(), result.channels());
    }

    private Result executeActions(QueryIntentV3 intent, String originalQuery, List<Long> kbIds, Long kbId,
                                  List<Long> entityIds, SelectionResult selected,
                                  Long tenantId, Long userId, List<ChatTurnDTO> history, String traceId,
                                  List<QueryStageTimingDTO> stages) {
        List<String> answers = new ArrayList<>();
        List<Evidence> finalEvidence = selected.evidences();
        StructuredQueryResult lastStructured = selected.structured();
        GenerationResult generation = null;
        String executionMode = intent.getSelection().getType() == QueryIntentV3.SelectionType.SEMANTIC
                ? "COMPOSITE" : "STRUCTURED";

        for (QueryIntentV3.Action action : intent.getActions()) {
            if (action == null || action.getType() == null) continue;
            switch (action.getType()) {
                case PROJECT_FIELDS -> {
                    ActionResult ar = project(intent, kbId, entityIds, action, stages);
                    if (!ar.ok()) return fail(ar.reason(), ar.message(), intent, stages);
                    answers.add(ar.answer());
                    lastStructured = ar.structured();
                }
                case LIST -> answers.add(renderList(entityIds));
                case COUNT -> answers.add(renderCount(entityIds.size(), selected.guarantee(), intent.getDomainCode()));
                case AGGREGATE -> {
                    ActionResult ar = aggregate(intent, kbId, entityIds, action, stages);
                    if (!ar.ok()) return fail(ar.reason(), ar.message(), intent, stages);
                    answers.add(ar.answer());
                    lastStructured = ar.structured();
                }
                case SUMMARIZE, ANSWER -> {
                    if (entityIds.size() > SEMANTIC_ENTITY_LIMIT) {
                        return clarify("EVIDENCE_ENTITY_LIMIT", "当前范围对象过多，请缩小范围后再做语义总结。", intent, stages);
                    }
                    String evidenceQuery = StrUtil.blankToDefault(action.getQuery(), originalQuery);
                    PlannedEvidenceRetriever.Result er = plannedRetriever.search(evidenceQuery, List.of(), kbIds,
                            entityIds.isEmpty() ? null : entityIds, EVIDENCE_TOP_K, tenantId, userId, traceId);
                    addRetrievalStages(stages, er.analysis(), "EVIDENCE_");
                    finalEvidence = er.evidences() == null ? List.of() : er.evidences();
                    stages.add(stage("ACTION_EVIDENCE", retrievalTotal(er.analysis()),
                            "query=“" + cut(evidenceQuery, 220) + "”; hardScope=" + compactIds(entityIds),
                            "evidence=" + evidenceSummary(finalEvidence)));
                    if (finalEvidence.isEmpty()) return fail("EMPTY_EVIDENCE", "目标对象内没有足够证据支持回答。", intent, stages);
                    long start = System.currentTimeMillis();
                    generation = answerPipeline.generateWithClaims(originalQuery, finalEvidence, history);
                    stages.add(stage("GENERATE_VERIFY", System.currentTimeMillis() - start,
                            "question=“" + cut(originalQuery, 220) + "”; evidenceCount=" + finalEvidence.size(),
                            generationSummary(generation)));
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        return fail("GENERATION_UNVERIFIED", "最终回答未通过证据验证。", intent, stages);
                    }
                    answers.add(generation.getAnswer());
                    executionMode = "HYBRID_RAG";
                }
                case COMPARE -> {
                    if (entityIds.size() < 2) {
                        return clarify("INSUFFICIENT_COMPARE_ENTITIES", "至少需要两个对象才能比较。", intent, stages);
                    }
                    if (entityIds.size() > SEMANTIC_ENTITY_LIMIT) {
                        return clarify("COMPARE_ENTITY_LIMIT", "比较对象过多，请缩小范围。", intent, stages);
                    }
                    String compareQuery = StrUtil.blankToDefault(action.getQuery(), originalQuery);
                    List<Evidence> all = new ArrayList<>();
                    for (Long entityId : entityIds) {
                        PlannedEvidenceRetriever.Result per = plannedRetriever.search(compareQuery, List.of(), kbIds,
                                List.of(entityId), 3, tenantId, userId, traceId);
                        if (per.evidences() != null) all.addAll(per.evidences());
                    }
                    stages.add(stage("ACTION_COMPARE_RETRIEVE", 0,
                            "query=“" + cut(compareQuery, 220) + "”; perEntityHardScope=" + compactIds(entityIds),
                            "evidenceCount=" + all.size() + "; coveredDocuments=" + documentIds(all)));
                    if (!documentIds(all).containsAll(entityIds)) {
                        return clarify("INSUFFICIENT_CROSS_ENTITY_COVERAGE", "比较对象的证据覆盖不足。", intent, stages);
                    }
                    generation = answerPipeline.generateWithClaims(originalQuery, all, history);
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        return fail("COMPARE_UNVERIFIED", "比较结果未通过证据验证。", intent, stages);
                    }
                    finalEvidence = all;
                    answers.add(generation.getAnswer());
                    executionMode = "CROSS_ENTITY_COMPARE";
                }
            }
        }

        if (answers.isEmpty()) return fail("EMPTY_ACTION_RESULT", "执行计划没有产生结果。", intent, stages);
        String answer = String.join("\n", answers);
        stages.add(stage("ANSWER", 0, "actionCount=" + intent.getActions().size(), "最终回答=“" + cut(answer, 700) + "”"));
        renumber(stages);
        return new Result(State.ANSWER, answer, null, null, executionMode, intent, entityIds,
                finalEvidence == null ? List.of() : finalEvidence, generation, lastStructured,
                selected.analysis(), selected.channels(), stages, selected.guarantee());
    }

    private ActionResult project(QueryIntentV3 intent, Long kbId, List<Long> entityIds,
                                 QueryIntentV3.Action action, List<QueryStageTimingDTO> stages) {
        if (kbId == null || action.getFields() == null || action.getFields().isEmpty()) {
            return ActionResult.fail("MISSING_PROJECTION", "字段投影条件不完整。");
        }
        FieldDefinition anchor = fieldRegistry.byCode(intent.getDomainCode(), action.getFields().get(0)).orElse(null);
        if (anchor == null) return ActionResult.fail("INVALID_PROJECTION_FIELD", "返回字段未注册。");
        metricRegistry.register(syntheticMetric(anchor, intent.getDomainCode()));
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.LIST).domainCode(intent.getDomainCode())
                .entityType(anchor.getEntityType()).scope(QueryScope.documentSet(kbId, entityIds))
                .metricCode(anchor.getFieldCode()).fieldCode(anchor.getFieldCode()).projections(action.getFields())
                .operation(Operation.NONE).filters(Map.of("publishedOnly", "true"))
                .resolvedEntities(entityIds).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        stages.add(stage("ACTION_PROJECT", System.currentTimeMillis() - start,
                "entityIds=" + compactIds(entityIds) + "; fields=" + action.getFields(), resultSummary(result)));
        if (result == null || result.isUnsupported()) {
            return ActionResult.fail("PROJECT_FAILED", result == null ? "字段投影无结果。" : result.getUnsupportedReason());
        }
        return ActionResult.ok(renderProjection(action.getFields(), rows(result), intent.getDomainCode()), result);
    }

    private ActionResult aggregate(QueryIntentV3 intent, Long kbId, List<Long> entityIds,
                                   QueryIntentV3.Action action, List<QueryStageTimingDTO> stages) {
        if (kbId == null || StrUtil.isBlank(action.getMetric())) {
            return ActionResult.fail("INVALID_AGGREGATE", "聚合指标不完整。");
        }
        MetricDefinition metric = metricRegistry.lookup(intent.getDomainCode(), action.getMetric()).orElse(null);
        if (metric == null) return ActionResult.fail("INVALID_METRIC", "聚合指标未注册。");
        Operation op;
        try {
            op = StrUtil.isBlank(action.getOperation()) ? Operation.NONE : Operation.valueOf(action.getOperation().toUpperCase());
        } catch (Exception e) {
            return ActionResult.fail("INVALID_OPERATION", "聚合运算不可执行。");
        }
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.AGGREGATE).domainCode(intent.getDomainCode())
                .entityType(metric.getEntityType()).scope(QueryScope.documentSet(kbId, entityIds))
                .metricCode(metric.getMetricCode()).operation(op).filters(Map.of("publishedOnly", "true"))
                .resolvedEntities(entityIds).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        stages.add(stage("ACTION_AGGREGATE", System.currentTimeMillis() - start,
                "metric=" + action.getMetric() + "; operation=" + op + "; entityIds=" + compactIds(entityIds),
                resultSummary(result)));
        if (result == null || result.isUnsupported()) {
            return ActionResult.fail("AGGREGATE_FAILED", result == null ? "聚合无结果。" : result.getUnsupportedReason());
        }
        return ActionResult.ok(action.getMetric() + " " + op + " = " + number(result.getValue()), result);
    }

    private List<Evidence> entityCandidates(List<Evidence> evidences, String domainCode) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        DomainEntityIdentityProvider provider = identityProviders.stream()
                .filter(p -> domainCode != null && domainCode.equalsIgnoreCase(p.domainCode())).findFirst().orElse(null);
        Map<String, Evidence> best = new LinkedHashMap<>();
        for (Evidence evidence : evidences) {
            if (evidence == null) continue;
            Long documentId = parseLong(evidence.getDocumentId());
            String key = provider == null ? null : provider.identityKey(evidence, documentId);
            if (StrUtil.isBlank(key)) key = documentId == null ? "CHUNK:" + evidence.getChunkId() : "DOC:" + documentId;
            Evidence old = best.get(key);
            if (old == null || score(evidence) > score(old)) best.put(key, evidence);
        }
        return best.values().stream().sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(SEMANTIC_ENTITY_LIMIT).toList();
    }

    private SelectionResult fromEvidence(List<Evidence> evidences, String guarantee,
                                         RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                         RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
        List<Long> ids = documentIds(evidences);
        return ids.isEmpty() ? SelectionResult.fail("EMPTY_ENTITY_SET", "没有检索到可定位的业务对象。")
                : SelectionResult.ok(ids, evidences, guarantee, null, analysis, channels);
    }

    private List<Evidence> filterDocuments(List<Evidence> evidences, List<Long> documentIds) {
        Set<Long> allowed = new LinkedHashSet<>(documentIds);
        return evidences.stream().filter(e -> allowed.contains(parseLong(e.getDocumentId()))).toList();
    }

    private List<Long> normalizeDomainEntities(List<Long> ids, String domainCode) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!"PATENT".equalsIgnoreCase(domainCode)) return distinct;
        Map<Long, KnowledgeDocumentRespDTO> docs = documentMap(distinct);
        Map<String, Long> unique = new LinkedHashMap<>();
        for (Long id : distinct) {
            unique.putIfAbsent(StrUtil.blankToDefault(patentIdentity(docs.get(id)), "DOC:" + id), id);
        }
        return new ArrayList<>(unique.values());
    }

    private String patentIdentity(KnowledgeDocumentRespDTO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            var meta = JSONUtil.parseObj(doc.getDomainMetadata());
            String app = normalize(meta.getStr("applicationNo"));
            if (StrUtil.isNotBlank(app)) return "APP:" + app;
            String pub = normalize(meta.getStr("publicationNo"));
            return StrUtil.isBlank(pub) ? null : "PUB:" + pub;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<Long, KnowledgeDocumentRespDTO> documentMap(List<Long> ids) {
        try {
            Map<Long, KnowledgeDocumentRespDTO> map = knowledgeApi.getDocumentMap(ids).getCheckedData();
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private MetricDefinition syntheticMetric(FieldDefinition field, String domainCode) {
        return MetricDefinition.builder().metricCode(field.getFieldCode()).domainCode(domainCode)
                .entityType(field.getEntityType()).valueType(field.getValueType()).supportedOperations(Set.of())
                .adapterKey(domainCode).aliases(field.getAliases()).displayName(fieldLabel(field.getFieldCode(), domainCode)).build();
    }

    private String renderProjection(List<String> fields, List<StructuredQueryResult.Row> rows, String domainCode) {
        if (rows.isEmpty()) return "当前范围内没有符合条件的数据。";
        StringBuilder sb = new StringBuilder("找到 ").append(rows.size()).append(" 个对象：\n");
        int index = 1;
        for (StructuredQueryResult.Row row : rows) {
            sb.append(index++).append(". ").append(StrUtil.blankToDefault(row.getEntityName(), "对象 #" + row.getEntityId()));
            for (String field : fields) {
                String value = row.getFields() == null ? null : row.getFields().get(field);
                sb.append("；").append(fieldLabel(field, domainCode)).append("：").append(StrUtil.blankToDefault(value, "未提供"));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String renderList(List<Long> ids) {
        if (ids.isEmpty()) return "当前范围内没有匹配对象。";
        Map<Long, KnowledgeDocumentRespDTO> docs = documentMap(ids);
        String names = ids.stream().map(id -> docs.get(id) != null && StrUtil.isNotBlank(docs.get(id).getName())
                ? docs.get(id).getName() : "对象 #" + id).collect(Collectors.joining("、"));
        return "找到 " + ids.size() + " 个匹配对象：" + names + "。";
    }

    private String renderCount(int count, String guarantee, String domainCode) {
        String noun = "PATENT".equalsIgnoreCase(domainCode) ? "件专利" : "个对象";
        boolean complete = guarantee != null && (guarantee.contains("COMPLETE") || guarantee.startsWith("CONTEXT"));
        return complete ? "当前条件下共有 " + count + " " + noun + "。"
                : "按当前检索条件找到 " + count + " " + noun + "；这是相关性/有限召回结果，不代表数学意义上的全集数量。";
    }

    private String fieldLabel(String code, String domainCode) {
        if (StrUtil.isBlank(code)) return "字段";
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field != null && code.equalsIgnoreCase(field.getFieldCode())
                    && field.getAliases() != null && !field.getAliases().isEmpty()) return field.getAliases().get(0);
        }
        return code;
    }

    private boolean needsSingleKb(QueryIntentV3 intent) {
        QueryIntentV3.SelectionType type = intent.getSelection().getType();
        if (type == QueryIntentV3.SelectionType.STRUCTURED_FILTER || type == QueryIntentV3.SelectionType.EXACT_ENTITY) return true;
        return intent.getActions().stream().anyMatch(a -> a != null && (a.getType() == QueryIntentV3.ActionType.PROJECT_FIELDS
                || a.getType() == QueryIntentV3.ActionType.AGGREGATE));
    }

    private void addRetrievalStages(List<QueryStageTimingDTO> target,
                                    RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis, String prefix) {
        if (analysis == null || analysis.getStages() == null) return;
        for (QueryStageTimingDTO source : analysis.getStages()) {
            if (source == null) continue;
            QueryStageTimingDTO copy = new QueryStageTimingDTO();
            copy.setStage(prefix + source.getStage());
            copy.setStatus(source.getStatus());
            copy.setElapsedMs(source.getElapsedMs());
            copy.setSkipped(source.getSkipped());
            copy.setErrorCode(source.getErrorCode());
            copy.setErrorMessage(source.getErrorMessage());
            copy.setModelCallId(source.getModelCallId());
            copy.setInputSummary(source.getInputSummary());
            copy.setOutputSummary(source.getOutputSummary());
            target.add(copy);
        }
    }

    private QueryStageTimingDTO stage(String name, long elapsedMs, String input, String output) {
        QueryStageTimingDTO stage = new QueryStageTimingDTO();
        stage.setStage(name);
        stage.setStatus("SUCCEEDED");
        stage.setElapsedMs(Math.max(0, elapsedMs));
        stage.setSkipped(false);
        stage.setInputSummary(cut(input, 950));
        stage.setOutputSummary(cut(output, 950));
        return stage;
    }

    private void renumber(List<QueryStageTimingDTO> stages) {
        int seq = 0;
        for (QueryStageTimingDTO stage : stages) if (stage != null) stage.setSeq(++seq);
    }

    private long retrievalTotal(RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis) {
        return analysis == null || analysis.getStages() == null ? 0 : analysis.getStages().stream()
                .filter(Objects::nonNull).map(QueryStageTimingDTO::getElapsedMs).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
    }

    private List<StructuredQueryResult.Row> rows(StructuredQueryResult result) {
        return result == null || result.getRows() == null ? List.of() : result.getRows();
    }

    private List<Long> documentIds(List<Evidence> evidences) {
        Set<Long> ids = new LinkedHashSet<>();
        if (evidences != null) {
            for (Evidence evidence : evidences) {
                Long id = evidence == null ? null : parseLong(evidence.getDocumentId());
                if (id != null) ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private String summarizeIntent(QueryIntentV3 intent) {
        if (intent == null) return "null";
        QueryIntentV3.Selection s = intent.getSelection();
        return "source=" + intent.getPlannerSource() + "; selection=" + (s == null ? "-" : s.getType())
                + (s != null && StrUtil.isNotBlank(s.getQuery()) ? " query=“" + cut(s.getQuery(), 160) + "”" : "")
                + (s != null && StrUtil.isNotBlank(s.getField()) ? " field=" + s.getField() + " op=" + s.getOperator() + " values=" + s.getValues() : "")
                + "; actions=" + intent.getActions().stream().map(a -> a.getType() + " fields=" + a.getFields() + " metric=" + a.getMetric()).toList()
                + "; completeness=" + intent.getCompleteness();
    }

    private String evidenceSummary(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return "[]";
        return evidences.stream().limit(6).map(e -> "{doc=" + e.getDocumentId() + ",name="
                + cut(StrUtil.nullToEmpty(e.getDocumentName()), 55) + ",score=" + e.getScore() + "}")
                .collect(Collectors.joining(",", "[", evidences.size() > 6 ? ",...]" : "]"));
    }

    private String resultSummary(StructuredQueryResult result) {
        if (result == null) return "null";
        if (result.isUnsupported()) return "UNSUPPORTED: " + result.getUnsupportedReason();
        return "rows=" + rows(result).size() + "; value=" + result.getValue() + "; truncated=" + result.isTruncated();
    }

    private String generationSummary(GenerationResult generation) {
        return generation == null ? "null" : "answer=“" + cut(generation.getAnswer(), 450)
                + "”; claimFail=" + generation.isClaimFail() + "; degraded=" + generation.isVerificationDegraded()
                + "; timedOut=" + generation.isTimedOut();
    }

    private String compactIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        return ids.size() <= 20 ? ids.toString() : ids.subList(0, 20) + "... total=" + ids.size();
    }

    private String number(Double value) {
        if (value == null) return "0";
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : String.format("%.2f", value);
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase();
    }

    private Long parseLong(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double score(Evidence evidence) {
        return evidence == null || evidence.getScore() == null ? 0D : evidence.getScore();
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private String cut(String value, int max) {
        if (value == null) return "-";
        String clean = value.replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private Result clarify(String reason, String question, QueryIntentV3 intent, List<QueryStageTimingDTO> stages) {
        stages.add(stage("ANSWER", 0, "requiresClarification=true", "clarify=“" + cut(question, 600) + "”"));
        renumber(stages);
        return new Result(State.CLARIFY, null, question, reason, "COMPOSITE", intent,
                List.of(), List.of(), null, null, null, null, stages, null);
    }

    private Result fail(String reason, String message, QueryIntentV3 intent, List<QueryStageTimingDTO> stages) {
        stages.add(stage("ANSWER", 0, "execution stopped", "unanswerable=“" + cut(message, 600) + "”; reason=" + reason));
        renumber(stages);
        return new Result(State.UNANSWERABLE, null, null, reason, "COMPOSITE", intent,
                List.of(), List.of(), null, null, null, null, stages, null);
    }

    public enum State { ANSWER, CLARIFY, UNANSWERABLE }

    public record Result(State state, String answer, String clarificationQuestion, String reasonCode,
                         String executionMode, QueryIntentV3 intent, List<Long> entityIds, List<Evidence> evidences,
                         GenerationResult generation, StructuredQueryResult structuredResult,
                         RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                         RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                         List<QueryStageTimingDTO> stages, String selectionGuarantee) { }

    private record SelectionResult(boolean ok, List<Long> entityIds, List<Evidence> evidences, String guarantee,
                                   StructuredQueryResult structured, RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                   RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                                   String reason, String message, String clarification) {
        static SelectionResult ok(List<Long> ids, List<Evidence> evidences, String guarantee,
                                  StructuredQueryResult structured,
                                  RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                  RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
            return new SelectionResult(true, ids == null ? List.of() : ids,
                    evidences == null ? List.of() : evidences, guarantee, structured,
                    analysis, channels, null, null, null);
        }

        static SelectionResult fail(String reason, String message) {
            return new SelectionResult(false, List.of(), List.of(), null, null, null, null, reason, message, null);
        }

        static SelectionResult clarify(String reason, String question) {
            return new SelectionResult(false, List.of(), List.of(), null, null, null, null, reason, null, question);
        }
    }

    private record ActionResult(boolean ok, String answer, StructuredQueryResult structured, String reason, String message) {
        static ActionResult ok(String answer, StructuredQueryResult structured) {
            return new ActionResult(true, answer, structured, null, null);
        }

        static ActionResult fail(String reason, String message) {
            return new ActionResult(false, null, null, reason, message);
        }
    }
}
