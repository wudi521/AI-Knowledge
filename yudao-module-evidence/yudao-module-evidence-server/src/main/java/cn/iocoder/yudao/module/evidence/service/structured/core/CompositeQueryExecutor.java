package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.semantics.SemanticsExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Composite Query Plan 执行器(CQ-02/38): 最小 DAG 编排。
 * <p>
 * ResolveScope → StructuredLookup → Filter → Aggregate → PerEntitySemantic → Synthesis。
 * 单步结构化查询(COUNT/SUM/LIST/TOP_N)由 {@link StructuredQueryService} 完成;
 * 无法结构化但已有明确实体集 → 追加 {@link SemanticsExecutionService} 逐实体语义执行。
 * <p>
 * 约束:
 * - 步骤数/实体数/模型调用数/deadline 受 plan budget 限制(超限 CLARIFY 或降级, 禁止无界执行);
 * - 结构化结果为确定性路径(0 LLM); 语义执行生成计入 modelCalls;
 * - 逐步校验 deadline, 超时返回 timedOut 降级。
 */
@Slf4j
@Component
public class CompositeQueryExecutor {

    private final StructuredQueryService structuredQueryService;
    private final SemanticsExecutionService semanticsExecutionService;

    public CompositeQueryExecutor(StructuredQueryService structuredQueryService,
                                  SemanticsExecutionService semanticsExecutionService) {
        this.structuredQueryService = structuredQueryService;
        this.semanticsExecutionService = semanticsExecutionService;
    }

    /** 执行请求 */
    public record Request(String query, Long kbId, String domainCode, List<ChatTurnDTO> history,
                          List<Long> explicitEntityIds, String fieldCodeHint,
                          Long tenantId, Long userId, String traceId,
                          CompositeQueryPlan.Budget budget) {
    }

    /** 执行结果(含组装所需全部数据) */
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
        int steps = 0;
        int modelCalls = 0;

        // ResolveScope + StructuredLookup + Filter + Aggregate(单步 handle 已含 scope/metric/field/operation 消解)
        steps++;
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

    /** 保序实体 id: 优先结构化执行结果 rows, 回退 plan 已消解实体集 */
    private List<Long> extractEntityIds(StructuredQueryResult result, StructuredQueryPlan plan) {
        if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
            return result.getRows().stream().map(StructuredQueryResult.Row::getEntityId).toList();
        }
        if (plan != null && plan.getResolvedEntities() != null) {
            return plan.getResolvedEntities();
        }
        return List.of();
    }

    /** PER_ENTITY_SEMANTIC: 逐实体语义执行, 受 budget(实体数/模型调用/deadline)约束 */
    private Result executeSemantic(Request req, StructuredQueryService.HandleResult structured,
                                   CompositeQueryPlan.Budget budget, long deadlineAt,
                                   int modelCalls, int steps) {
        List<Long> ids = structured.semanticEntityIds() != null ? structured.semanticEntityIds() : List.of();
        if (ids.size() > budget.maxEntities()) {
            // 超限: 不静默截断, 反问要求缩小范围
            return new Result(StructuredQueryService.State.CLARIFY, null,
                    "共 " + ids.size() + " 个对象, 一次最多可逐项说明 " + budget.maxEntities() + " 个, 请缩小范围后再问。",
                    StructuredFailureReason.AMBIGUOUS_SCOPE, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                    structured.plan(), null, null, null, false, null);
        }
        modelCalls++; // 语义生成 1 次(逐实体检索不计模型调用; 生成为聚合一次)
        if (modelCalls > budget.maxModelCalls() || System.currentTimeMillis() > deadlineAt) {
            return timedOut(structured.plan());
        }
        steps++;
        if (steps > budget.maxSteps() || System.currentTimeMillis() > deadlineAt) {
            return timedOut(structured.plan());
        }
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
                    ExecutionMode.CODE_PER_ENTITY_SEMANTIC, structured.plan(),
                    ids, sr.evidences(), sr.generation(), false, null);
        }
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                StructuredFailureReason.EMPTY_RESULT_SET, ExecutionMode.CODE_PER_ENTITY_SEMANTIC,
                structured.plan(), ids, sr.evidences(), sr.generation(), false, null);
    }

    private Result timedOut(StructuredQueryPlan plan) {
        return new Result(StructuredQueryService.State.UNANSWERABLE, null, null,
                "PLAN_TIMEOUT", null, plan, null, null, null, true, null);
    }
}
