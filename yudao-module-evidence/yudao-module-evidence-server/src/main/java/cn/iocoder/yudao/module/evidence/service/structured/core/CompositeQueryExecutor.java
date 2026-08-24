package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.planner.QueryClass;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlan;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlannerFacade;
import cn.iocoder.yudao.module.evidence.service.semantics.ExactTextExecutionService;
import cn.iocoder.yudao.module.evidence.service.semantics.SemanticsExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Composite Query Plan 执行器：Query Planner V2 → Structured / ExactText / Per-Entity / Cross-Entity Compare。
 * 普通 Hybrid/Scoped RAG 返回 NOT_STRUCTURED，继续复用既有稳定检索主链。
 */
@Slf4j
@Component
public class CompositeQueryExecutor {

    private final StructuredQueryService structuredQueryService;
    private final SemanticsExecutionService semanticsExecutionService;
    private final QueryPlannerFacade queryPlanner;
    private final ExactTextExecutionService exactTextExecutionService;

    @Autowired
    public CompositeQueryExecutor(StructuredQueryService structuredQueryService,
                                  SemanticsExecutionService semanticsExecutionService,
                                  QueryPlannerFacade queryPlanner,
                                  ExactTextExecutionService exactTextExecutionService) {
        this.structuredQueryService = structuredQueryService;
        this.semanticsExecutionService = semanticsExecutionService;
        this.queryPlanner = queryPlanner;
        this.exactTextExecutionService = exactTextExecutionService;
    }

    /** 源码兼容构造器，供既有单测使用。 */
    public CompositeQueryExecutor(StructuredQueryService structuredQueryService,
                                  SemanticsExecutionService semanticsExecutionService,
                                  QueryPlannerFacade queryPlanner) {
        this(structuredQueryService, semanticsExecutionService, queryPlanner, null);
    }

    public record Request(String query, Long kbId, String domainCode, List<ChatTurnDTO> history,
                          List<Long> explicitEntityIds, String fieldCodeHint,
                          Long tenantId, Long userId, String traceId,
                          CompositeQueryPlan.Budget budget) {
    }

    public record Result(StructuredQueryService.State state, String answer, String clarificationQuestion,
                         String reasonCode, String executionMode, StructuredQueryPlan plan,
                         List<Long> entityIds, List<Evidence> evidences, GenerationResult generation,
                         boolean timedOut, StructuredQueryResult structuredResult) {
    }

    public Result execute(Request req) {
        if (req == null || StrUtil.isBlank(req.query()) || req.kbId() == null) {
            return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                    StructuredFailureReason.AMBIGUOUS_SCOPE, null, null, null, null, null, false, null);
        }
        CompositeQueryPlan.Budget budget = req.budget() != null ? req.budget() : CompositeQueryPlan.Budget.defaults();
        long deadlineAt = System.currentTimeMillis() + budget.deadlineMs();

        QueryPlan typedPlan = queryPlanner.plan(req.query(), req.domainCode(), req.history(),
                req.explicitEntityIds(), null);
        if (typedPlan != null) {
            log.info("[execute][planner source={}, class={}, mode={}, scope={}, query={}]",
                    typedPlan.getPlannerSource(), typedPlan.getQueryClass(), typedPlan.getExecutionMode(),
                    typedPlan.getScopeType(), StrUtil.maxLength(req.query(), 80));
            if (typedPlan.getQueryClass() == QueryClass.CLARIFY || typedPlan.isRequiresClarification()) {
                return new Result(StructuredQueryService.State.CLARIFY, null,
                        StrUtil.blankToDefault(typedPlan.getClarificationQuestion(), "请补充查询范围或比较标准。"),
                        StrUtil.blankToDefault(typedPlan.getReasonCode(), "PLANNER_CLARIFY"),
                        typedPlan.getExecutionMode() != null ? typedPlan.getExecutionMode().code() : null,
                        null, null, null, null, false, null);
            }
            if (typedPlan.getExecutionMode() == ExecutionMode.EXACT_TEXT_SEARCH) {
                return executeExactText(req, typedPlan, deadlineAt);
            }
            if (typedPlan.getExecutionMode() == ExecutionMode.CROSS_ENTITY_COMPARE) {
                return executeCrossEntityCompare(req, typedPlan, budget, deadlineAt);
            }
            if (typedPlan.getExecutionMode() == ExecutionMode.PER_ENTITY_SEMANTIC
                    && req.explicitEntityIds() != null && !req.explicitEntityIds().isEmpty()) {
                return executePerEntityPlan(req, typedPlan, budget, deadlineAt);
            }
            if (typedPlan.getQueryClass() == QueryClass.SEMANTIC_QUERY
                    && typedPlan.getExecutionMode() != ExecutionMode.STRUCTURED) {
                return new Result(StructuredQueryService.State.NOT_STRUCTURED, null, null, null,
                        typedPlan.getExecutionMode() != null ? typedPlan.getExecutionMode().code() : null,
                        null, null, null, null, false, null);
            }
        }

        int steps = 1;
        int modelCalls = 0;
        StructuredQueryService.HandleResult structured = structuredQueryService.handle(
                req.query(), req.kbId(), req.domainCode(), req.history(),
                req.explicitEntityIds(), req.fieldCodeHint());
        if (steps > budget.maxSteps() || System.currentTimeMillis() > deadlineAt) {
            return timedOut(structured == null ? null : structured.plan());
        }
        switch (structured.state()) {
            case ANSWER:
                return new Result(StructuredQueryService.State.ANSWER, structured.answer(), null, null,
                        ExecutionMode.CODE_STRUCTURED, structured.plan(),
                        extractEntityIds(structured.result(), structured.plan()),
                        null, null, false, structured.result());
            case CLARIFY:
                return new Result(StructuredQueryService.State.CLARIFY, null, structured.clarificationQuestion(),
                        structured.reasonCode(), ExecutionMode.CODE_STRUCTURED, structured.plan(),
                        null, null, null, false, null);
            case UNANSWERABLE:
                return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                        structured.reasonCode(), ExecutionMode.CODE_STRUCTURED, structured.plan(),
                        null, null, null, false, structured.result());
            case SEMANTIC:
                return executeSemantic(req, structured, budget, deadlineAt, modelCalls, steps);
            case NOT_STRUCTURED:
            default:
                return new Result(StructuredQueryService.State.NOT_STRUCTURED, null, null, null,
                        null, null, null, null, null, false, null);
        }
    }

    private Result executeExactText(Request req, QueryPlan typedPlan, long deadlineAt) {
        List<Long> ids = typedPlan.getEntityIds() == null ? List.of() : typedPlan.getEntityIds();
        StructuredQueryPlan plan = semanticPlan(req, ids, ExecutionMode.CODE_EXACT_TEXT_SEARCH);
        if (System.currentTimeMillis() > deadlineAt) return timedOut(plan);
        if (exactTextExecutionService == null) {
            return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                    "EXACT_TEXT_EXECUTOR_UNAVAILABLE", ExecutionMode.CODE_EXACT_TEXT_SEARCH,
                    plan, ids, List.of(), null, false, null);
        }
        ExactTextExecutionService.Result sr = exactTextExecutionService.execute(
                req.query(), typedPlan.getExactText(), req.kbId(), ids,
                req.tenantId(), req.userId(), req.traceId());
        if (!sr.answerable()) {
            return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                    sr.reasonCode(), ExecutionMode.CODE_EXACT_TEXT_SEARCH,
                    plan, ids, sr.evidences(), null, false, null);
        }
        return new Result(StructuredQueryService.State.ANSWER, sr.answer(), null, null,
                ExecutionMode.CODE_EXACT_TEXT_SEARCH, plan, ids, sr.evidences(), null, false, null);
    }

    private Result executeCrossEntityCompare(Request req, QueryPlan typedPlan,
                                             CompositeQueryPlan.Budget budget, long deadlineAt) {
        List<Long> ids = typedPlan.getEntityIds() != null ? typedPlan.getEntityIds() : List.of();
        if (!ids.isEmpty() && ids.size() > budget.maxEntities()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + ids.size() + " 个对象，一次最多比较 " + budget.maxEntities() + " 个，请缩小范围后再问。",
                    "COMPARE_ENTITY_LIMIT", ExecutionMode.CODE_CROSS_ENTITY_COMPARE,
                    semanticPlan(req, ids, ExecutionMode.CODE_CROSS_ENTITY_COMPARE), null, null, null, false, null);
        }
        if (System.currentTimeMillis() > deadlineAt) return timedOut(semanticPlan(req, ids, ExecutionMode.CODE_CROSS_ENTITY_COMPARE));

        boolean requireAll = "ALL".equalsIgnoreCase(typedPlan.getCoveragePolicy());
        SemanticsExecutionService.CompareResult sr = semanticsExecutionService.executeCompare(
                req.query(), req.kbId(), req.domainCode(), ids,
                req.tenantId(), req.userId(), req.history(), req.traceId(), requireAll);
        if (sr.overLimit()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "当前范围有 " + sr.entityIds().size() + " 个对象，一次最多比较 " + sr.limit() + " 个，请缩小范围。",
                    "COMPARE_ENTITY_LIMIT", ExecutionMode.CODE_CROSS_ENTITY_COMPARE,
                    semanticPlan(req, sr.entityIds(), ExecutionMode.CODE_CROSS_ENTITY_COMPARE), null, null, null, false, null);
        }
        if (sr.coverageInsufficient()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "当前比较范围的证据覆盖不足：需要至少两个不同对象，并且每个待比较对象都应有可用证据。请缩小范围或检查知识文档后重试。",
                    "INSUFFICIENT_CROSS_ENTITY_COVERAGE", ExecutionMode.CODE_CROSS_ENTITY_COMPARE,
                    semanticPlan(req, sr.entityIds(), ExecutionMode.CODE_CROSS_ENTITY_COMPARE),
                    sr.entityIds(), sr.evidences(), null, false, null);
        }
        if (sr.generation() != null && StrUtil.isNotBlank(sr.generation().getAnswer())) {
            return new Result(StructuredQueryService.State.ANSWER, sr.generation().getAnswer(), null, null,
                    ExecutionMode.CODE_CROSS_ENTITY_COMPARE,
                    semanticPlan(req, sr.entityIds(), ExecutionMode.CODE_CROSS_ENTITY_COMPARE),
                    sr.entityIds(), sr.evidences(), sr.generation(), false, null);
        }
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_CROSS_ENTITY_COMPARE,
                semanticPlan(req, sr.entityIds(), ExecutionMode.CODE_CROSS_ENTITY_COMPARE),
                sr.entityIds(), sr.evidences(), sr.generation(), false, null);
    }

    private Result executePerEntityPlan(Request req, QueryPlan typedPlan,
                                        CompositeQueryPlan.Budget budget, long deadlineAt) {
        List<Long> ids = typedPlan.getEntityIds() != null ? typedPlan.getEntityIds() : List.of();
        if (ids.size() > budget.maxEntities()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + ids.size() + " 个对象，一次最多可逐项说明 " + budget.maxEntities() + " 个，请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    semanticPlan(req, ids, ExecutionMode.CODE_PER_ENTITY_SEMANTIC), null, null, null, false, null);
        }
        if (System.currentTimeMillis() > deadlineAt) return timedOut(semanticPlan(req, ids, ExecutionMode.CODE_PER_ENTITY_SEMANTIC));
        SemanticsExecutionService.Result sr = semanticsExecutionService.execute(
                req.query(), req.kbId(), ids, req.tenantId(), req.userId(), req.history(), req.traceId());
        if (sr.overLimit()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + sr.entityIds().size() + " 个对象，一次最多可逐项说明 " + sr.limit() + " 个，请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    semanticPlan(req, ids, ExecutionMode.CODE_PER_ENTITY_SEMANTIC), null, null, null, false, null);
        }
        if (sr.generation() != null && StrUtil.isNotBlank(sr.generation().getAnswer())) {
            return new Result(StructuredQueryService.State.ANSWER, sr.generation().getAnswer(), null, null,
                    ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    semanticPlan(req, ids, ExecutionMode.CODE_PER_ENTITY_SEMANTIC),
                    ids, sr.evidences(), sr.generation(), false, null);
        }
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                semanticPlan(req, ids, ExecutionMode.CODE_PER_ENTITY_SEMANTIC),
                ids, sr.evidences(), sr.generation(), false, null);
    }

    private StructuredQueryPlan semanticPlan(Request req, List<Long> ids, String executionMode) {
        return StructuredQueryPlan.builder()
                // route 仅承载兼容主路由；内部执行模式由 Result.executionMode 单独表达。
                .route("HYBRID_RAG")
                .domainCode(req.domainCode())
                .scope(ids == null || ids.isEmpty() ? QueryScope.currentKb(req.kbId()) : QueryScope.documentSet(req.kbId(), ids))
                .resolvedEntities(ids)
                .build();
    }

    private List<Long> extractEntityIds(StructuredQueryResult result, StructuredQueryPlan plan) {
        if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
            return result.getRows().stream().map(StructuredQueryResult.Row::getEntityId).toList();
        }
        if (plan != null && plan.getResolvedEntities() != null) return plan.getResolvedEntities();
        return List.of();
    }

    private Result executeSemantic(Request req, StructuredQueryService.HandleResult structured,
                                   CompositeQueryPlan.Budget budget, long deadlineAt,
                                   int modelCalls, int steps) {
        List<Long> ids = structured.semanticEntityIds() != null ? structured.semanticEntityIds() : List.of();
        boolean crossEntity = ids.isEmpty() && structured.plan() != null
                && ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC.equals(structured.plan().getRoute());
        if (crossEntity) return executeCrossEntitySemantic(req, structured, budget, deadlineAt, modelCalls, steps);
        if (ids.size() > budget.maxEntities()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + ids.size() + " 个对象, 一次最多可逐项说明 " + budget.maxEntities() + " 个, 请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    structured.plan(), null, null, null, false, null);
        }
        modelCalls++;
        if (modelCalls > budget.maxModelCalls() || System.currentTimeMillis() > deadlineAt) return timedOut(structured.plan());
        steps++;
        if (steps > budget.maxSteps() || System.currentTimeMillis() > deadlineAt) return timedOut(structured.plan());
        SemanticsExecutionService.Result sr = semanticsExecutionService.execute(
                req.query(), req.kbId(), ids, req.tenantId(), req.userId(), req.history(), req.traceId());
        if (sr.overLimit()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + sr.entityIds().size() + " 个对象, 一次最多可逐项说明 " + sr.limit() + " 个, 请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    structured.plan(), null, null, null, false, null);
        }
        if (sr.evidences() == null || sr.evidences().isEmpty()) {
            return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                    StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    structured.plan(), ids, List.of(), null, false, null);
        }
        if (sr.generation() != null && StrUtil.isNotBlank(sr.generation().getAnswer())) {
            return new Result(StructuredQueryService.State.ANSWER, sr.generation().getAnswer(), null, null,
                    ExecutionMode.CODE_PER_ENTITY_SEMANTIC, structured.plan(), ids, sr.evidences(), sr.generation(), false, null);
        }
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                structured.plan(), ids, sr.evidences(), sr.generation(), false, null);
    }

    private Result executeCrossEntitySemantic(Request req, StructuredQueryService.HandleResult structured,
                                              CompositeQueryPlan.Budget budget, long deadlineAt,
                                              int modelCalls, int steps) {
        modelCalls++;
        if (modelCalls > budget.maxModelCalls() || System.currentTimeMillis() > deadlineAt) return timedOut(structured.plan());
        steps++;
        if (steps > budget.maxSteps() || System.currentTimeMillis() > deadlineAt) return timedOut(structured.plan());
        SemanticsExecutionService.Result sr = semanticsExecutionService.executeCrossEntity(
                req.query(), req.kbId(), req.tenantId(), req.userId(), req.history(), req.traceId());
        if (sr.overLimit()) {
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "知识库共 " + sr.entityIds().size() + " 个对象, 一次最多可逐项说明 " + sr.limit() + " 个, 请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC,
                    structured.plan(), null, null, null, false, null);
        }
        if (sr.evidences() == null || sr.evidences().isEmpty()) {
            return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                    StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC,
                    structured.plan(), sr.entityIds(), List.of(), null, false, null);
        }
        if (sr.generation() != null && StrUtil.isNotBlank(sr.generation().getAnswer())) {
            return new Result(StructuredQueryService.State.ANSWER, sr.generation().getAnswer(), null, null,
                    ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC, structured.plan(), sr.entityIds(), sr.evidences(), sr.generation(), false, null);
        }
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC,
                structured.plan(), sr.entityIds(), sr.evidences(), sr.generation(), false, null);
    }

    private Result timedOut(StructuredQueryPlan plan) {
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                "PLAN_TIMEOUT", null, plan, null, null, null, true, null);
    }
}
