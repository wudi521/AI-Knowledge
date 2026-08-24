package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.planner.QueryClass;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlan;
import cn.iocoder.yudao.module.evidence.service.planner.QueryPlannerFacade;
import cn.iocoder.yudao.module.evidence.service.semantics.ExactTextExecutionService;
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

@ExtendWith(MockitoExtension.class)
class CompositeQueryExecutorExactTextTest {

    @Mock StructuredQueryService structuredQueryService;
    @Mock SemanticsExecutionService semanticsExecutionService;
    @Mock QueryPlannerFacade queryPlanner;
    @Mock ExactTextExecutionService exactTextExecutionService;

    @Test
    void exactTextDispatchBypassesStructuredAndSemanticRag() {
        CompositeQueryExecutor executor = new CompositeQueryExecutor(
                structuredQueryService, semanticsExecutionService, queryPlanner, exactTextExecutionService);
        QueryPlan typedPlan = QueryPlan.builder()
                .queryClass(QueryClass.SEMANTIC_QUERY)
                .executionMode(ExecutionMode.EXACT_TEXT_SEARCH)
                .domainCode("PATENT")
                .scopeType("CURRENT_KB")
                .entityIds(List.of())
                .exactText("粒子化磁涌")
                .build();
        when(queryPlanner.plan(any(), any(), any(), any(), any())).thenReturn(typedPlan);
        Evidence evidence = Evidence.builder().chunkId(101L).documentId("67").content("粒子化磁涌").build();
        when(exactTextExecutionService.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExactTextExecutionService.Result("找到 1 个片段", List.of(evidence), true, null));

        CompositeQueryExecutor.Result result = executor.execute(new CompositeQueryExecutor.Request(
                "原文中包含“粒子化磁涌”吗？", 6L, "PATENT", List.of(), null, null,
                1L, 9L, "q-exact", CompositeQueryPlan.Budget.defaults()));

        assertThat(result.state()).isEqualTo(StructuredQueryService.State.ANSWER);
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.CODE_EXACT_TEXT_SEARCH);
        assertThat(result.answer()).isEqualTo("找到 1 个片段");
        assertThat(result.evidences()).hasSize(1);
        verify(structuredQueryService, never()).handle(any(), any(), any(), any(), any(), any());
        verify(semanticsExecutionService, never()).execute(any(), any(), any(), any(), any(), any(), any());
        verify(semanticsExecutionService, never()).executeCrossEntity(any(), any(), any(), any(), any(), any());
    }
}
