package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.planner.ComparisonType;
import cn.iocoder.yudao.module.evidence.service.planner.QueryClass;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlan;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlannerFacade;
import cn.iocoder.yudao.module.evidence.service.semantics.SemanticsExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Query Planner V2 必须在 Structured/Hybrid 执行之前生效。 */
@ExtendWith(MockitoExtension.class)
class CompositeQueryExecutorPlannerTest {

    @Mock StructuredQueryService structuredQueryService;
    @Mock SemanticsExecutionService semanticsExecutionService;
    @Mock QueryPlannerFacade queryPlanner;

    @Test
    void crossEntityComparisonBypassesStructuredLookup() {
        CompositeQueryExecutor executor = new CompositeQueryExecutor(
                structuredQueryService, semanticsExecutionService, queryPlanner);
        QueryPlan plan = QueryPlan.builder()
                .queryClass(QueryClass.SEMANTIC_QUERY)
                .executionMode(ExecutionMode.CROSS_ENTITY_COMPARE)
                .domainCode("PATENT")
                .scopeType("CURRENT_KB")
                .comparisonType(ComparisonType.SIMILARITY)
                .coveragePolicy("ALL")
                .build();
        when(queryPlanner.plan(any(), any(), any(), any(), any())).thenReturn(plan);
        Evidence a = Evidence.builder().chunkId(1L).documentId("65").content("a").build();
        Evidence b = Evidence.builder().chunkId(2L).documentId("66").content("b").build();
        GenerationResult generation = GenerationResult.builder().answer("A 与 B 较相似").build();
        when(semanticsExecutionService.executeCompare(any(), any(), any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(new SemanticsExecutionService.CompareResult(
                        List.of(a, b), generation, List.of(65L, 66L), List.of(65L, 66L), false, 10, false));

        CompositeQueryExecutor.Result result = executor.execute(new CompositeQueryExecutor.Request(
                "哪些专利比较相似？", 6L, "PATENT", List.of(), null, null,
                1L, 1L, "q-test", CompositeQueryPlan.Budget.defaults()));

        assertThat(result.state()).isEqualTo(StructuredQueryService.State.ANSWER);
        assertThat(result.answer()).isEqualTo("A 与 B 较相似");
        assertThat(result.entityIds()).containsExactly(65L, 66L);
        verify(structuredQueryService, never()).handle(any(), any(), any(), any(), any(), any());
    }
}
