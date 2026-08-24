package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.semantics.SemanticsExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CQ-02/38 Composite Query Plan 执行器: ResolveScope→StructuredLookup→Aggregate→PerEntitySemantic→Synthesis。
 * 验证 budget(步骤/实体/模型调用/deadline)约束、结构化/语义 fallback 编排、reasonCode 透传。
 */
class CompositeQueryExecutorTest {

    private StructuredQueryService structuredQueryService;
    private SemanticsExecutionService semanticsExecutionService;
    private CompositeQueryExecutor executor;

    @BeforeEach
    void setUp() {
        structuredQueryService = mock(StructuredQueryService.class);
        semanticsExecutionService = mock(SemanticsExecutionService.class);
        executor = new CompositeQueryExecutor(structuredQueryService, semanticsExecutionService);
    }

    private CompositeQueryExecutor.Request request(CompositeQueryPlan.Budget budget) {
        return new CompositeQueryExecutor.Request("它们的技术方案分别是什么？", 6L, "PATENT",
                List.of(), List.of(101L, 102L), null, 7L, 42L, "q-1", budget);
    }

    private CompositeQueryPlan.Budget budget(int maxSteps, int maxEntities, int maxModelCalls, long deadlineMs) {
        return new CompositeQueryPlan.Budget(maxSteps, maxEntities, maxModelCalls, deadlineMs);
    }

    private StructuredQueryPlan plan(String route) {
        return StructuredQueryPlan.builder().route(route).domainCode("PATENT").build();
    }

    private StructuredQueryService.HandleResult answer(String answer, StructuredQueryResult result) {
        return new StructuredQueryService.HandleResult(StructuredQueryService.State.ANSWER, plan("STRUCTURED_QUERY"),
                null, result, answer, null, null, null);
    }

    @Test
    void structuredAnswer_passesThroughEntityIdsFromRows() {
        StructuredQueryResult result = StructuredQueryResult.builder()
                .rows(List.of(
                        StructuredQueryResult.Row.builder().entityId(101L).build(),
                        StructuredQueryResult.Row.builder().entityId(102L).build()))
                .build();
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any()))
                .thenReturn(answer("当前共有 2 件专利。", result));

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.ANSWER);
        assertThat(r.answer()).isEqualTo("当前共有 2 件专利。");
        assertThat(r.executionMode()).isEqualTo(ExecutionMode.CODE_STRUCTURED);
        assertThat(r.entityIds()).containsExactly(101L, 102L);
        verifyNoInteractions(semanticsExecutionService);
    }

    @Test
    void semanticFallback_executesPerEntitySemantic() {
        StructuredQueryService.HandleResult semantic = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.SEMANTIC, plan("PER_ENTITY_SEMANTIC"), null, null,
                null, null, StructuredFailureReason.MISSING_METRIC, List.of(101L, 102L));
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(semantic);
        when(semanticsExecutionService.execute(any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(new SemanticsExecutionService.Result(
                        List.of(Evidence.builder().chunkId(1L).documentId("101").build(),
                                Evidence.builder().chunkId(2L).documentId("102").build()),
                        GenerationResult.builder().answer("专利A：核心X；专利B：核心Y").claims(List.of()).build(),
                        List.of(101L, 102L), false, 100));

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.ANSWER);
        assertThat(r.executionMode()).isEqualTo(ExecutionMode.CODE_PER_ENTITY_SEMANTIC);
        assertThat(r.answer()).contains("核心X").contains("核心Y");
        assertThat(r.entityIds()).containsExactly(101L, 102L);
        assertThat(r.evidences()).hasSize(2);
    }

    @Test
    void semanticOverEntityBudget_clarifiesWithoutTruncation() {
        StructuredQueryService.HandleResult semantic = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.SEMANTIC, plan("PER_ENTITY_SEMANTIC"), null, null,
                null, null, StructuredFailureReason.MISSING_METRIC, List.of(101L, 102L, 103L));
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(semantic);

        // maxEntities=2 < 3 → CLARIFY, 不静默截断
        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 2, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.CLARIFY);
        assertThat(r.reasonCode()).isEqualTo(StructuredFailureReason.AMBIGUOUS_SCOPE);
        assertThat(r.clarificationQuestion()).contains("2").contains("缩小范围");
        verifyNoInteractions(semanticsExecutionService);
    }

    @Test
    void semanticNoEvidence_unanswerableEmptyResultSet() {
        StructuredQueryService.HandleResult semantic = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.SEMANTIC, plan("PER_ENTITY_SEMANTIC"), null, null,
                null, null, StructuredFailureReason.MISSING_METRIC, List.of(101L));
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(semantic);
        when(semanticsExecutionService.execute(any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(new SemanticsExecutionService.Result(List.of(), null, List.of(101L), false, 100));

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.UNANSWERABLE);
        assertThat(r.reasonCode()).isEqualTo(StructuredFailureReason.EMPTY_RESULT_SET);
    }

    @Test
    void structuredClarify_passesThroughReasonCode() {
        StructuredQueryService.HandleResult clarify = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.CLARIFY, plan("CLARIFY"), null, null, null,
                "请明确指标。", StructuredFailureReason.MISSING_METRIC, null);
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(clarify);

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.CLARIFY);
        assertThat(r.reasonCode()).isEqualTo(StructuredFailureReason.MISSING_METRIC);
        assertThat(r.clarificationQuestion()).isEqualTo("请明确指标。");
    }

    @Test
    void structuredUnanswerable_passesThroughReasonCode() {
        StructuredQueryService.HandleResult un = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.UNANSWERABLE, plan("STRUCTURED_QUERY"), null,
                StructuredQueryResult.unsupported("指标未注册: FOO"), null, null,
                StructuredFailureReason.MISSING_METRIC, null);
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(un);

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.UNANSWERABLE);
        assertThat(r.reasonCode()).isEqualTo(StructuredFailureReason.MISSING_METRIC);
    }

    @Test
    void stepBudgetExceeded_returnsTimedOut() {
        // maxSteps=0 → handle 即算 1 步, 触发超限降级
        CompositeQueryExecutor.Result r = executor.execute(request(budget(0, 100, 12, 60_000)));
        assertThat(r.timedOut()).isTrue();
        assertThat(r.state()).isEqualTo(StructuredQueryService.State.UNANSWERABLE);
    }

    @Test
    void notStructured_returnsNotStructured() {
        StructuredQueryService.HandleResult ns = new StructuredQueryService.HandleResult(
                StructuredQueryService.State.NOT_STRUCTURED, null, null, null, null, null, null, null);
        when(structuredQueryService.handle(any(), any(), any(), any(), any(), any())).thenReturn(ns);

        CompositeQueryExecutor.Result r = executor.execute(request(budget(5, 100, 12, 60_000)));

        assertThat(r.state()).isEqualTo(StructuredQueryService.State.NOT_STRUCTURED);
        verifyNoInteractions(semanticsExecutionService);
    }
}
