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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query Engine V3：唯一 QueryIntent → Selection → bounded retrieval → EntitySet → Action 执行内核。
 *
 * <p>自然语言只在 QueryPlannerV3 中理解一次。这里以及下游 Retrieval/Structured 只消费 typed intent。</p>
 */
@Slf4j
@Component
public class QueryEngineV3 {

    private static final int MAX_SELECTED_ENTITIES = 10;
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
            return unanswerable("INVALID_QUERY_CONTEXT", "查询上下文不完整。", null, stages);
        }

        long contextStart = System.currentTimeMillis();
        stages.add(stage("QUERY_CONTEXT", 0, System.currentTimeMillis() - contextStart,
                "query=“" + limit(query, 260) + "”; requestedKbIds=" + kbIds + "; domain=" + domainCode,
                "effectiveKbIds=" + kbIds + "; historyTurns=" + size(history)
                        + "; previousEntityIds=" + safeList(explicitEntityIds)));

        QueryIntentV3 intent = planner.plan(query, domainCode, history, explicitEntityIds, traceId);
        stages.add(stage("SEMANTIC_PLAN", 0, intent == null || intent.getPlannerElapsedMs() == null ? 0 : intent.getPlannerElapsedMs(),
                "自然语言 + Domain Schema + lexical facts + context",
                summarizeIntent(intent)));
        QueryIntentValidatorV3.Validation validation = validator.validate(intent);
        stages.add(stage("PLAN_VALIDATE", 0, 0,
                "typed QueryIntentV3",
                validation.valid() ? "PASS" : "FAIL reason=" + validation.reasonCode()));
        if (!validation.valid()) {
            return unanswerable(validation.reasonCode(), "查询计划未通过白名单校验。", intent, stages);
        }
        if (intent.isRequiresClarification()) {
            return clarify(StrUtil.blankToDefault(intent.getReasonCode(), "PLANNER_CLARIFY"),
                    StrUtil.blankToDefault(intent.getClarificationQuestion(), "请补充查询条件。"), intent, stages);
        }

        if (kbIds.size() > 1 && requiresStructuredAction(intent)) {
            return clarify("MULTI_KB_STRUCTURED_UNSUPPORTED",
                    "当前结构化动作需要限定到一个知识库，请先选择单个知识库。", intent, stages);
        }
        Long kbId = kbIds.size() == 1 ? kbIds.get(0) : null;

        SelectionResult selection = select(intent, query, kbIds, kbId, explicitEntityIds,
                tenantId, userId, traceId, stages);
        if (!selection.answerable()) {
            if (selection.clarification() != null) return clarify(selection.reasonCode(), selection.clarification(), intent, stages);
            return unanswerable(selection.reasonCode(), selection.message(), intent, stages);
        }

        List<Long> entityIds = normalizeDomainEntities(selection.entityIds(), domainCode)
                .stream().limit(MAX_SELECTED_ENTITIES).toList();
        stages.add(stage("ENTITY_SET", 0, 0,
                "selection=" + intent.getSelection().getType(),
                "entityIds=" + entityIds + "; count=" + entityIds.size()
                        + "; guarantee=" + selection.guarantee()));

        return executeActions(intent, query, kbIds, kbId, entityIds, selection,
                tenantId, userId, history, traceId, stages);
    }

    private SelectionResult select(QueryIntentV3 intent, String originalQuery, List<Long> kbIds, Long kbId,
                                   List<Long> explicitEntityIds, Long tenantId, Long userId, String traceId,
                                   List<QueryStageTimingDTO> stages) {
        QueryIntentV3.Selection selection = intent.getSelection();
        return switch (selection.getType()) {
            case CURRENT_SCOPE -> currentScope(kbIds, intent.getDomainCode());
            case RESULT_SET -> {
                if (explicitEntityIds == null || explicitEntityIds.isEmpty()) {
                    yield SelectionResult.clarify("MISSING_RESULT_SET", "当前没有可复用的上一轮对象集合。");
                }
                yield SelectionResult.ok(explicitEntityIds, List.of(), "CONTEXT_COMPLETE", null, null);
            }
            case EXACT_ENTITY, STRUCTURED_FILTER -> structuredSelect(selection, intent, kbId, stages);
            case SEMANTIC -> semanticSelect(selection, intent.getDomainCode(), kbIds, tenantId, userId, traceId, stages);
            case EXACT_TEXT -> exactTextSelect(selection, intent.getDomainCode(), kbIds, tenantId, userId, traceId, stages);
        };
    }

    private SelectionResult currentScope(List<Long> kbIds, String domainCode) {
        List<Long> ids = new ArrayList<>();
        try {
            for (Long kbId : kbIds) {
                List<Long> current = knowledgeApi.getPublishedDocumentIds(kbId).getCheckedData();
                if (current != null) ids.addAll(current);
            }
        } catch (Exception e) {
            return SelectionResult.fail("CURRENT_SCOPE_FAILED", "无法读取当前已发布对象范围。");
        }
        return SelectionResult.ok(normalizeDomainEntities(ids, domainCode), List.of(), "STRUCTURED_COMPLETE", null, null);
    }

    private SelectionResult structuredSelect(QueryIntentV3.Selection selection, QueryIntentV3 intent,
                                               Long kbId, List<QueryStageTimingDTO> stages) {
        if (kbId == null) return SelectionResult.clarify("MULTI_KB_FILTER_UNSUPPORTED", "结构化过滤需要先选择单个知识库。");
        FieldDefinition field = fieldRegistry.byCode(intent.getDomainCode(), selection.getField()).orElse(null);
        if (field == null || !field.isFilterable()) return SelectionResult.fail("FILTER_FIELD_UNAVAILABLE", "过滤字段当前不可执行。");
        MetricDefinition metric = syntheticMetric(field, intent.getDomainCode());
        metricRegistry.register(metric);
        FilterOperator operator;
        try { operator = FilterOperator.valueOf(selection.getOperator().toUpperCase()); }
        catch (Exception e) { return SelectionResult.fail("INVALID_FILTER_OPERATOR", "过滤运算符不可执行。"); }
        FilterExpression filter = FilterExpression.condition(field.getFieldCode(), operator, selection.getValues());
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.LIST)
                .domainCode(intent.getDomainCode()).entityType(field.getEntityType())
                .scope(QueryScope.currentKb(kbId)).metricCode(field.getFieldCode()).fieldCode(field.getFieldCode())
                .projections(List.of(field.getFieldCode())).operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true")).filterExpression(filter).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        long elapsed = System.currentTimeMillis() - start;
        stages.add(stage("STRUCTURED_SELECT", 0, elapsed,
                "field=" + field.getFieldCode() + "; operator=" + operator + "; values=" + selection.getValues(),
                resultSummary(result)));
        if (result == null || result.isUnsupported()) {
            return SelectionResult.fail("STRUCTURED_SELECTION_FAILED",
                    result == null ? "结构化选择没有返回结果。" : result.getUnsupportedReason());
        }
        List<Long> ids = result.getRows() == null ? List.of() : result.getRows().stream()
                .map(StructuredQueryResult.Row::getEntityId).filter(java.util.Objects::nonNull).distinct().toList();
        return SelectionResult.ok(ids, List.of(), "STRUCTURED_COMPLETE", result, null);
    }

    private SelectionResult semanticSelect(QueryIntentV3.Selection selection, String domainCode,
                                            List<Long> kbIds, Long tenantId, Long userId, String traceId,
                                            List<QueryStageTimingDTO> stages) {
        String semanticQuery = StrUtil.blankToDefault(selection.getQuery(), "");
        PlannedEvidenceRetriever.Result round1 = plannedRetriever.search(semanticQuery,
                selection.getQueryVariants(), kbIds, null, SELECTION_TOP_K, tenantId, userId, traceId);
        addRetrievalStages(stages, round1.analysis(), "I1_");
        List<Evidence> first = rankEntities(round1.evidences(), domainCode);
        stages.add(stage("RETRIEVAL_ITERATION_1", 0, retrievalTotal(round1.analysis()),
                "selectionQuery=“" + limit(semanticQuery, 220) + "”; variants=" + selection.getQueryVariants(),
                "entityCandidates=" + evidenceSummary(first)));

        RetrievalRefinementService.Decision decision = refinementService.decide(semanticQuery, first, traceId);
        stages.add(stage("RETRIEVAL_DECIDE", 0, decision.elapsedMs(),
                "iteration=1; candidates=" + evidenceSummary(first),
                "decision=" + decision.type() + "; source=" + decision.source()
                        + "; reasonCode=" + decision.reasonCode() + "; nextQueries=" + decision.nextQueries()
                        + "; selectedDocumentIds=" + decision.selectedDocumentIds()));

        if (decision.type() == RetrievalRefinementService.DecisionType.ABSTAIN) {
            return SelectionResult.fail(decision.reasonCode(), "当前检索结果不足以可靠确定目标对象。");
        }
        if (decision.type() == RetrievalRefinementService.DecisionType.ACCEPT) {
            return selectionFromEvidence(first, "SEMANTIC_BEST_EFFORT", round1.analysis(), round1.channels());
        }

        List<Long> hardScope = decision.type() == RetrievalRefinementService.DecisionType.NARROW
                ? decision.selectedDocumentIds() : null;
        List<String> next = decision.nextQueries();
        String round2Query = !next.isEmpty() ? next.get(0) : semanticQuery;
        List<String> round2Variants = next.size() <= 1 ? List.of() : next.subList(1, next.size());
        PlannedEvidenceRetriever.Result round2 = plannedRetriever.search(round2Query, round2Variants,
                kbIds, hardScope, SELECTION_TOP_K, tenantId, userId, traceId);
        addRetrievalStages(stages, round2.analysis(), "I2_");
        List<Evidence> second = rankEntities(round2.evidences(), domainCode);
        stages.add(stage("RETRIEVAL_ITERATION_2", 0, retrievalTotal(round2.analysis()),
                "decision=" + decision.type() + "; query=“" + limit(round2Query, 220)
                        + "”; variants=" + round2Variants + "; hardScope=" + safeList(hardScope),
                "entityCandidates=" + evidenceSummary(second)));
        if (second.isEmpty()) {
            if (!first.isEmpty()) {
                stages.add(stage("RETRIEVAL_FINAL_DECISION", 0, 0,
                        "round2 empty", "fallback=iteration1 candidates; reason=SECOND_ROUND_EMPTY"));
                return selectionFromEvidence(first, "SEMANTIC_BEST_EFFORT", round1.analysis(), round1.channels());
            }
            return SelectionResult.fail("EMPTY_SEMANTIC_SELECTION", "没有检索到足够相关的对象。");
        }
        stages.add(stage("RETRIEVAL_FINAL_DECISION", 0, 0,
                "maximumIterations=2", "ACCEPT iteration2; no further autonomous loop"));
        return selectionFromEvidence(second, "SEMANTIC_BEST_EFFORT", round2.analysis(), round2.channels());
    }

    private SelectionResult exactTextSelect(QueryIntentV3.Selection selection, String domainCode, List<Long> kbIds,
                                             Long tenantId, Long userId, String traceId,
                                             List<QueryStageTimingDTO> stages) {
        PlannedEvidenceRetriever.Result result = plannedRetriever.exactText(selection.getQuery(), kbIds, null,
                20, tenantId, userId, traceId);
        addRetrievalStages(stages, result.analysis(), "EXACT_");
        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        String guarantee = Boolean.TRUE.equals(result.totalHitsExact()) ? "EXACT_CHUNK_COMPLETE" : "BEST_EFFORT";
        return selectionFromEvidence(rankEntities(evidences, domainCode), guarantee, result.analysis(), result.channels());
    }

    private Result executeActions(QueryIntentV3 intent, String originalQuery, List<Long> kbIds, Long kbId,
                                  List<Long> entityIds, SelectionResult selection,
                                  Long tenantId, Long userId, List<ChatTurnDTO> history, String traceId,
                                  List<QueryStageTimingDTO> stages) {
        List<String> parts = new ArrayList<>();
        StructuredQueryResult lastStructured = selection.structuredResult();
        List<Evidence> finalEvidence = selection.evidences();
        GenerationResult generation = null;
        String executionMode = intent.getSelection().getType() == QueryIntentV3.SelectionType.SEMANTIC
                ? "COMPOSITE" : "STRUCTURED";

        for (QueryIntentV3.Action action : intent.getActions()) {
            if (action == null || action.getType() == null) continue;
            switch (action.getType()) {
                case PROJECT_FIELDS -> {
                    ActionResult ar = projectFields(intent, kbId, entityIds, action, stages);
                    if (!ar.ok()) return unanswerable(ar.reasonCode(), ar.message(), intent, stages);
                    parts.add(ar.answer());
                    lastStructured = ar.structuredResult();
                    executionMode = intent.getSelection().getType() == QueryIntentV3.SelectionType.SEMANTIC ? "COMPOSITE" : "STRUCTURED";
                }
                case LIST -> parts.add(renderEntityList(entityIds, intent.getDomainCode()));
                case COUNT -> parts.add(renderCount(entityIds.size(), selection.guarantee(), intent.getDomainCode()));
                case AGGREGATE -> {
                    ActionResult ar = aggregate(intent, kbId, entityIds, action, stages);
                    if (!ar.ok()) return unanswerable(ar.reasonCode(), ar.message(), intent, stages);
                    parts.add(ar.answer());
                    lastStructured = ar.structuredResult();
                }
                case SUMMARIZE, ANSWER -> {
                    String evidenceQuery = StrUtil.blankToDefault(action.getQuery(), originalQuery);
                    PlannedEvidenceRetriever.Result er = plannedRetriever.search(evidenceQuery, List.of(), kbIds,
                            entityIds.isEmpty() ? null : entityIds, EVIDENCE_TOP_K, tenantId, userId, traceId);
                    addRetrievalStages(stages, er.analysis(), "EVIDENCE_");
                    finalEvidence = er.evidences() == null ? List.of() : er.evidences();
                    stages.add(stage("ACTION_EVIDENCE", 0, retrievalTotal(er.analysis()),
                            "query=“" + limit(evidenceQuery, 240) + "”; hardScopeEntityIds=" + entityIds,
                            "evidence=" + evidenceSummary(finalEvidence)));
                    if (finalEvidence.isEmpty()) return unanswerable("EMPTY_EVIDENCE", "目标对象内没有足够证据支持回答。", intent, stages);
                    long genStart = System.currentTimeMillis();
                    generation = answerPipeline.generateWithClaims(originalQuery, finalEvidence, history);
                    stages.add(stage("GENERATE_VERIFY", 0, System.currentTimeMillis() - genStart,
                            "question=“" + limit(originalQuery, 240) + "”; evidenceCount=" + finalEvidence.size(),
                            generationSummary(generation)));
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        return unanswerable("GENERATION_UNVERIFIED", "证据已找到，但最终回答未通过证据验证。", intent, stages);
                    }
                    parts.add(generation.getAnswer());
                    executionMode = "HYBRID_RAG";
                }
                case COMPARE -> {
                    if (entityIds.size() < 2) return clarify("INSUFFICIENT_COMPARE_ENTITIES", "至少需要两个对象才能比较。", intent, stages);
                    List<Evidence> compareEvidence = new ArrayList<>();
                    String compareQuery = StrUtil.blankToDefault(action.getQuery(), originalQuery);
                    for (Long entityId : entityIds) {
                        PlannedEvidenceRetriever.Result per = plannedRetriever.search(compareQuery, List.of(), kbIds,
                                List.of(entityId), 3, tenantId, userId, traceId);
                        if (per.evidences() != null) compareEvidence.addAll(per.evidences());
                    }
                    finalEvidence = compareEvidence;
                    stages.add(stage("ACTION_COMPARE_RETRIEVE", 0, 0,
                            "query=“" + limit(compareQuery, 240) + "”; perEntityHardScope=" + entityIds,
                            "evidenceCount=" + compareEvidence.size() + "; coveredDocuments=" + documentIds(compareEvidence)));
                    if (!documentIds(compareEvidence).containsAll(entityIds)) {
                        return clarify("INSUFFICIENT_CROSS_ENTITY_COVERAGE",
                                "比较对象的证据覆盖不足，请缩小范围或检查文档内容。", intent, stages);
                    }
                    generation = answerPipeline.generateWithClaims(originalQuery, compareEvidence, history);
                    if (generation == null || StrUtil.isBlank(generation.getAnswer()) || generation.isClaimFail()) {
                        return unanswerable("COMPARE_UNVERIFIED", "比较结果未能通过证据验证。", intent, stages);
                    }
                    parts.add(generation.getAnswer());
                    executionMode = "CROSS_ENTITY_COMPARE";
                }
            }
        }

        if (parts.isEmpty()) return unanswerable("EMPTY_ACTION_RESULT", "执行计划没有产生可返回结果。", intent, stages);
        String answer = String.join("\n", parts);
        stages.add(stage("ANSWER", 0, 0, "actionCount=" + intent.getActions().size(), "最终回答=“" + limit(answer, 700) + "”"));
        renumber(stages);
        return new Result(State.ANSWER, answer, null, null, executionMode, intent,
                entityIds, finalEvidence == null ? List.of() : finalEvidence, generation, lastStructured,
                selection.analysis(), selection.channels(), stages, selection.guarantee());
    }

    private ActionResult projectFields(QueryIntentV3 intent, Long kbId, List<Long> entityIds,
                                       QueryIntentV3.Action action, List<QueryStageTimingDTO> stages) {
        if (kbId == null) return ActionResult.fail("MULTI_KB_PROJECT_UNSUPPORTED", "字段投影需要单知识库范围。");
        if (action.getFields() == null || action.getFields().isEmpty()) return ActionResult.fail("MISSING_PROJECTION", "未指定返回字段。");
        FieldDefinition anchor = fieldRegistry.byCode(intent.getDomainCode(), action.getFields().get(0)).orElse(null);
        if (anchor == null) return ActionResult.fail("INVALID_PROJECTION_FIELD", "返回字段未注册。");
        MetricDefinition metric = syntheticMetric(anchor, intent.getDomainCode());
        metricRegistry.register(metric);
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.LIST).domainCode(intent.getDomainCode())
                .entityType(anchor.getEntityType()).scope(QueryScope.documentSet(kbId, entityIds))
                .metricCode(anchor.getFieldCode()).fieldCode(anchor.getFieldCode()).projections(action.getFields())
                .operation(Operation.NONE).filters(Map.of("publishedOnly", "true")).resolvedEntities(entityIds).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        long elapsed = System.currentTimeMillis() - start;
        stages.add(stage("ACTION_PROJECT", 0, elapsed,
                "entityIds=" + entityIds + "; fields=" + action.getFields(), resultSummary(result)));
        if (result == null || result.isUnsupported()) return ActionResult.fail("PROJECT_FAILED",
                result == null ? "字段投影未返回。" : result.getUnsupportedReason());
        return ActionResult.ok(renderProjection(action.getFields(), result.getRows(), intent.getDomainCode()), result);
    }

    private ActionResult aggregate(QueryIntentV3 intent, Long kbId, List<Long> entityIds,
                                   QueryIntentV3.Action action, List<QueryStageTimingDTO> stages) {
        if (kbId == null || StrUtil.isBlank(action.getMetric())) return ActionResult.fail("INVALID_AGGREGATE", "聚合指标不完整。");
        MetricDefinition metric = metricRegistry.lookup(intent.getDomainCode(), action.getMetric()).orElse(null);
        if (metric == null) return ActionResult.fail("INVALID_METRIC", "聚合指标未注册。");
        Operation op;
        try { op = StrUtil.isBlank(action.getOperation()) ? Operation.NONE : Operation.valueOf(action.getOperation().toUpperCase()); }
        catch (Exception e) { return ActionResult.fail("INVALID_OPERATION", "聚合运算未注册。"); }
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY").queryType(QueryType.AGGREGATE).domainCode(intent.getDomainCode())
                .entityType(metric.getEntityType()).scope(QueryScope.documentSet(kbId, entityIds))
                .metricCode(metric.getMetricCode()).operation(op).filters(Map.of("publishedOnly", "true"))
                .resolvedEntities(entityIds).build();
        long start = System.currentTimeMillis();
        StructuredQueryResult result = structuredExecutor.execute(plan);
        stages.add(stage("ACTION_AGGREGATE", 0, System.currentTimeMillis() - start,
                "entityIds=" + entityIds + "; metric=" + action.getMetric() + "; operation=" + op,
                resultSummary(result)));
        if (result == null || result.isUnsupported()) return ActionResult.fail("AGGREGATE_FAILED",
                result == null ? "聚合没有返回。" : result.getUnsupportedReason());
        String value = result.getValue() == null ? "0" : (result.getValue() == Math.floor(result.getValue())
                ? String.valueOf(result.getValue().longValue()) : String.format("%.2f", result.getValue()));
        return ActionResult.ok(action.getMetric() + " " + op + " = " + value, result);
    }

    private String renderProjection(List<String> fields, List<StructuredQueryResult.Row> rows, String domainCode) {
        if (rows == null || rows.isEmpty()) return "当前范围内没有符合条件的数据。";
        StringBuilder sb = new StringBuilder("找到 ").append(rows.size()).append(" 个对象：\n");
        int i = 1;
        for (StructuredQueryResult.Row row : rows) {
            sb.append(i++).append(". ").append(StrUtil.blankToDefault(row.getEntityName(), "对象" + row.getEntityId()));
            List<String> values = new ArrayList<>();
            for (String field : fields) {
                String value = row.getFields() == null ? null : row.getFields().get(field);
                values.add(fieldLabel(field, domainCode) + "：" + StrUtil.blankToDefault(value, "未提供"));
            }
            if (!values.isEmpty()) sb.append("；").append(String.join("；", values));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String renderEntityList(List<Long> ids, String domainCode) {
        if (ids == null || ids.isEmpty()) return "当前范围内没有匹配对象。";
        Map<Long, KnowledgeDocumentRespDTO> docs = documentMap(ids);
        String body = ids.stream().map(id -> {
            KnowledgeDocumentRespDTO d = docs.get(id);
            return d != null && StrUtil.isNotBlank(d.getName()) ? d.getName() : "对象 #" + id;
        }).collect(Collectors.joining("、"));
        return "找到 " + ids.size() + " 个匹配对象：" + body + "。";
    }

    private String renderCount(int count, String guarantee, String domainCode) {
        String noun = "PATENT".equalsIgnoreCase(domainCode) ? "件专利" : "个对象";
        if (guarantee != null && guarantee.startsWith("SEMANTIC")) {
            return "按当前语义相关性检索，找到 " + count + " " + noun + "；这是相关性结果，不代表数学意义上的全集数量。";
        }
        return "当前条件下共有 " + count + " " + noun + "。";
    }

    private MetricDefinition syntheticMetric(FieldDefinition field, String domainCode) {
        return MetricDefinition.builder().metricCode(field.getFieldCode()).domainCode(domainCode)
                .entityType(field.getEntityType()).valueType(field.getValueType()).supportedOperations(Set.of())
                .adapterKey(domainCode).aliases(field.getAliases()).displayName(fieldLabel(field.getFieldCode(), domainCode)).build();
    }

    private String fieldLabel(String fieldCode, String domainCode) {
        if (StrUtil.isBlank(fieldCode)) return "字段";
        for (FieldDefinition f : fieldRegistry.all(domainCode)) {
            if (f != null && fieldCode.equalsIgnoreCase(f.getFieldCode()) && f.getAliases() != null && !f.getAliases().isEmpty()) {
                return f.getAliases().get(0);
            }
        }
        return fieldCode;
    }

    private List<Evidence> rankEntities(List<Evidence> evidences, String domainCode) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        DomainEntityIdentityProvider provider = identityProviders.stream()
                .filter(p -> domainCode != null && domainCode.equalsIgnoreCase(p.domainCode())).findFirst().orElse(null);
        Map<String, Evidence> best = new LinkedHashMap<>();
        for (Evidence e : evidences) {
            if (e == null) continue;
            Long docId = parseLong(e.getDocumentId());
            String identity = provider == null ? null : provider.identityKey(e, docId);
            if (StrUtil.isBlank(identity)) identity = docId == null ? "CHUNK:" + e.getChunkId() : "DOC:" + docId;
            Evidence old = best.get(identity);
            if (old == null || score(e) > score(old)) best.put(identity, e);
        }
        return best.values().stream().sorted(Comparator.comparingDouble(this::score).reversed()).limit(MAX_SELECTED_ENTITIES).toList();
    }

    private SelectionResult selectionFromEvidence(List<Evidence> evidence, String guarantee,
                                                   RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                                   RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
        List<Long> ids = documentIds(evidence);
        if (ids.isEmpty()) return SelectionResult.fail("EMPTY_ENTITY_SET", "没有检索到可定位的业务对象。");
        return SelectionResult.ok(ids, evidence, guarantee, null, analysis, channels);
    }

    private List<Long> normalizeDomainEntities(List<Long> ids, String domainCode) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!"PATENT".equalsIgnoreCase(domainCode)) return distinct;
        Map<Long, KnowledgeDocumentRespDTO> docs = documentMap(distinct);
        Map<String, Long> unique = new LinkedHashMap<>();
        for (Long id : distinct) {
            KnowledgeDocumentRespDTO doc = docs.get(id);
            String key = patentIdentity(doc);
            unique.putIfAbsent(StrUtil.blankToDefault(key, "DOC:" + id), id);
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
        } catch (Exception e) { return null; }
    }

    private Map<Long, KnowledgeDocumentRespDTO> documentMap(List<Long> ids) {
        try {
            Map<Long, KnowledgeDocumentRespDTO> map = knowledgeApi.getDocumentMap(ids).getCheckedData();
            return map == null ? Map.of() : map;
        } catch (Exception e) { return Map.of(); }
    }

    private List<Long> documentIds(List<Evidence> evidences) {
        Set<Long> ids = new LinkedHashSet<>();
        if (evidences != null) for (Evidence e : evidences) {
            Long id = e == null ? null : parseLong(e.getDocumentId());
            if (id != null) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    private void addRetrievalStages(List<QueryStageTimingDTO> out, RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                    String prefix) {
        if (analysis == null || analysis.getStages() == null) return;
        for (QueryStageTimingDTO source : analysis.getStages()) {
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
            out.add(copy);
        }
    }

    private long retrievalTotal(RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis) {
        if (analysis == null || analysis.getStages() == null) return 0;
        return analysis.getStages().stream().filter(java.util.Objects::nonNull)
                .map(QueryStageTimingDTO::getElapsedMs)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    private QueryStageTimingDTO stage(String name, long seq, long elapsed, String input, String output) {
        QueryStageTimingDTO s = new QueryStageTimingDTO();
        s.setStage(name); s.setSeq((int) seq); s.setStatus("SUCCEEDED"); s.setElapsedMs(elapsed); s.setSkipped(false);
        s.setInputSummary(limit(input, 950)); s.setOutputSummary(limit(output, 950)); return s;
    }

    private void renumber(List<QueryStageTimingDTO> stages) {
        int seq = 0;
        for (QueryStageTimingDTO s : stages) if (s != null) s.setSeq(++seq);
    }

    private String summarizeIntent(QueryIntentV3 intent) {
        if (intent == null) return "null";
        QueryIntentV3.Selection s = intent.getSelection();
        return "source=" + intent.getPlannerSource() + "; selection=" + (s == null ? "-" : s.getType())
                + (s != null && StrUtil.isNotBlank(s.getQuery()) ? "(query=“" + limit(s.getQuery(), 180) + "”)" : "")
                + (s != null && StrUtil.isNotBlank(s.getField()) ? "(field=" + s.getField() + ",op=" + s.getOperator() + ",values=" + s.getValues() + ")" : "")
                + "; actions=" + intent.getActions().stream().map(a -> a.getType() + " fields=" + a.getFields()
                + " metric=" + a.getMetric() + " op=" + a.getOperation()).toList()
                + "; completeness=" + intent.getCompleteness();
    }

    private String evidenceSummary(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return "[]";
        return evidence.stream().limit(6).map(e -> "{doc=" + e.getDocumentId() + ",name="
                + limit(StrUtil.nullToEmpty(e.getDocumentName()), 60) + ",score=" + e.getScore() + "}")
                .collect(Collectors.joining(",", "[", evidence.size() > 6 ? ",...]" : "]"));
    }

    private String resultSummary(StructuredQueryResult result) {
        if (result == null) return "null";
        if (result.isUnsupported()) return "UNSUPPORTED: " + result.getUnsupportedReason();
        return "rows=" + (result.getRows() == null ? 0 : result.getRows().size())
                + "; value=" + result.getValue() + "; truncated=" + result.isTruncated();
    }

    private String generationSummary(GenerationResult generation) {
        if (generation == null) return "null";
        return "answer=“" + limit(generation.getAnswer(), 500) + "”; claimFail=" + generation.isClaimFail()
                + "; verificationDegraded=" + generation.isVerificationDegraded()
                + "; timedOut=" + generation.isTimedOut();
    }

    private boolean requiresStructuredAction(QueryIntentV3 intent) {
        if (intent == null || intent.getActions() == null) return false;
        return intent.getActions().stream().anyMatch(a -> a != null && (a.getType() == QueryIntentV3.ActionType.PROJECT_FIELDS
                || a.getType() == QueryIntentV3.ActionType.AGGREGATE));
    }

    private double score(Evidence e) { return e == null || e.getScore() == null ? 0D : e.getScore(); }
    private Long parseLong(String value) { try { return StrUtil.isBlank(value) ? null : Long.parseLong(value); } catch (Exception e) { return null; } }
    private String normalize(String value) { return value == null ? null : value.replaceAll("\\s+", "").toUpperCase(); }
    private int size(List<?> list) { return list == null ? 0 : list.size(); }
    private String safeList(List<Long> list) { return list == null ? "[]" : list.toString(); }
    private String limit(String value, int max) { return value == null ? "-" : (value.length() <= max ? value : value.substring(0, max) + "..."); }

    private Result clarify(String reasonCode, String question, QueryIntentV3 intent, List<QueryStageTimingDTO> stages) {
        stages.add(stage("ANSWER", 0, 0, "plan requires clarification", "clarify=“" + limit(question, 600) + "”"));
        renumber(stages);
        return new Result(State.CLARIFY, null, question, reasonCode, "COMPOSITE", intent,
                List.of(), List.of(), null, null, null, null, stages, null);
    }

    private Result unanswerable(String reasonCode, String message, QueryIntentV3 intent, List<QueryStageTimingDTO> stages) {
        stages.add(stage("ANSWER", 0, 0, "execution stopped", "unanswerable=“" + limit(message, 600) + "”; reason=" + reasonCode));
        renumber(stages);
        return new Result(State.UNANSWERABLE, null, null, reasonCode, "COMPOSITE", intent,
                List.of(), List.of(), null, null, null, null, stages, null);
    }

    public enum State { ANSWER, CLARIFY, UNANSWERABLE }

    public record Result(State state, String answer, String clarificationQuestion, String reasonCode,
                         String executionMode, QueryIntentV3 intent, List<Long> entityIds,
                         List<Evidence> evidences, GenerationResult generation,
                         StructuredQueryResult structuredResult,
                         RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                         RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                         List<QueryStageTimingDTO> stages, String selectionGuarantee) { }

    private record SelectionResult(boolean answerable, List<Long> entityIds, List<Evidence> evidences,
                                   String guarantee, StructuredQueryResult structuredResult,
                                   RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                   RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                                   String reasonCode, String message, String clarification) {
        static SelectionResult ok(List<Long> ids, List<Evidence> evidence, String guarantee,
                                  StructuredQueryResult structured, RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                                  RetrievalSearchRespDTO.RetrievalChannelStatDTO channels) {
            return new SelectionResult(true, ids == null ? List.of() : ids, evidence == null ? List.of() : evidence,
                    guarantee, structured, analysis, channels, null, null, null);
        }
        static SelectionResult ok(List<Long> ids, List<Evidence> evidence, String guarantee,
                                  StructuredQueryResult structured, RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis) {
            return ok(ids, evidence, guarantee, structured, analysis, null);
        }
        static SelectionResult fail(String reason, String message) {
            return new SelectionResult(false, List.of(), List.of(), null, null, null, null, reason, message, null);
        }
        static SelectionResult clarify(String reason, String question) {
            return new SelectionResult(false, List.of(), List.of(), null, null, null, null, reason, null, question);
        }
    }

    private record ActionResult(boolean ok, String answer, StructuredQueryResult structuredResult,
                                String reasonCode, String message) {
        static ActionResult ok(String answer, StructuredQueryResult result) { return new ActionResult(true, answer, result, null, null); }
        static ActionResult fail(String reason, String message) { return new ActionResult(false, null, null, reason, message); }
    }
}
