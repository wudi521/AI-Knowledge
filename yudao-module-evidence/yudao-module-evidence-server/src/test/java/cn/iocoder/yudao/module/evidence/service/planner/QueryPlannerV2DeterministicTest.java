package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.model.api.ModelApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Query Planner V2 确定性 Golden Test：明确场景必须 0 LLM。 */
@ExtendWith(MockitoExtension.class)
class QueryPlannerV2DeterministicTest {

    @Mock CompletenessGuard completenessGuard;
    @Mock DomainFieldRegistry fieldRegistry;
    @Mock DomainMetricRegistry metricRegistry;
    @Mock ModelApi modelApi;
    @Mock PromptSupport promptSupport;
    @Mock QueryPlanValidator validator;

    private QueryPlannerV2 planner;

    @BeforeEach
    void setUp() {
        planner = new QueryPlannerV2(completenessGuard, fieldRegistry, metricRegistry,
                modelApi, promptSupport, validator);
    }

    @Test
    void comparisonIsDeterministicAndDoesNotCallLlm() {
        QueryPlan plan = planner.plan("哪些专利比较相似？", "PATENT", List.of(), null);
        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.CROSS_ENTITY_COMPARE);
        assertThat(plan.getComparisonType()).isEqualTo(ComparisonType.PAIR_COMPARE);
        verifyNoInteractions(modelApi);
    }

    @Test
    void subjectiveComparisonWithoutCriterionClarifies() {
        QueryPlan plan = planner.plan("这几个专利哪个更好？", "PATENT", List.of(), null);
        assertThat(plan.getQueryClass()).isEqualTo(QueryClass.CLARIFY);
        assertThat(plan.getReasonCode()).isEqualTo("MISSING_COMPARISON_CRITERION");
        assertThat(plan.getClarificationQuestion()).contains("标准");
        verifyNoInteractions(modelApi);
    }

    @Test
    void exactTextSearchWinsBeforeGenericStructuredSignal() {
        QueryPlan plan = planner.plan("哪些专利原文出现过永磁体？", "PATENT", List.of(), null);
        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.EXACT_TEXT_SEARCH);
        assertThat(plan.getCompletenessPolicy()).isEqualTo(CompletenessPolicy.COMPLETE_REQUIRED);
        verifyNoInteractions(modelApi);
    }
}
