package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Planner Golden Test：比较/逐实体语义必须在“哪些/分别”结构化关键词之前识别。 */
@ExtendWith(MockitoExtension.class)
class QueryPlannerFacadeTest {

    @Mock
    private QueryPlannerV2 delegate;

    @Test
    void comparisonWinsOverStructuredKeywords() {
        QueryPlannerFacade facade = new QueryPlannerFacade(delegate);
        QueryPlan plan = facade.plan("哪些专利比较相似？", "PATENT", List.of(), List.of(), null);

        assertThat(plan.getQueryClass()).isEqualTo(QueryClass.SEMANTIC_QUERY);
        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.CROSS_ENTITY_COMPARE);
        assertThat(plan.getComparisonType()).isEqualTo(ComparisonType.PAIR_COMPARE);
        assertThat(plan.getCoveragePolicy()).isEqualTo("ALL");
        verifyNoInteractions(delegate);
    }

    @Test
    void previousResultSemanticRunsPerEntity() {
        QueryPlannerFacade facade = new QueryPlannerFacade(delegate);
        QueryPlan plan = facade.plan("这三个专利核心技术分别是什么？", "PATENT", List.of(),
                List.of(65L, 66L, 67L), null);

        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.PER_ENTITY_SEMANTIC);
        assertThat(plan.getEntityIds()).containsExactly(65L, 66L, 67L);
        assertThat(plan.getScopeType()).isEqualTo("PREVIOUS_RESULT_SET");
        verifyNoInteractions(delegate);
    }

    @Test
    void commonalityAndDifferenceHaveDedicatedComparisonType() {
        QueryPlannerFacade facade = new QueryPlannerFacade(delegate);
        assertThat(facade.plan("这些专利有什么共同点？", "PATENT", List.of(), List.of(1L, 2L), null)
                .getComparisonType()).isEqualTo(ComparisonType.COMMONALITY);
        assertThat(facade.plan("这些专利有什么区别？", "PATENT", List.of(), List.of(1L, 2L), null)
                .getComparisonType()).isEqualTo(ComparisonType.DIFFERENCE);
    }
}
