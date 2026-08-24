package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QueryPlannerFacadeExactTextTest {

    @Mock QueryPlannerV2 delegate;

    @Test
    void quotedExactTextIsExtractedWithoutCallingLlmPlanner() {
        QueryPlannerFacade planner = new QueryPlannerFacade(delegate);

        QueryPlan plan = planner.plan("原文中包含“粒子化磁涌”吗？", "PATENT", List.of(), List.of(), null);

        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.EXACT_TEXT_SEARCH);
        assertThat(plan.getExactText()).isEqualTo("粒子化磁涌");
        assertThat(plan.getPlannerSource()).isEqualTo("DETERMINISTIC");
        assertThat(plan.isRequiresClarification()).isFalse();
        verify(delegate, never()).plan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exactIntentWithoutPhraseClarifiesInsteadOfHybridFallback() {
        QueryPlannerFacade planner = new QueryPlannerFacade(delegate);

        QueryPlan plan = planner.plan("帮我精确搜索原文", "PATENT", List.of(), List.of(), null);

        assertThat(plan.getQueryClass()).isEqualTo(QueryClass.CLARIFY);
        assertThat(plan.getExecutionMode()).isEqualTo(ExecutionMode.EXACT_TEXT_SEARCH);
        assertThat(plan.getReasonCode()).isEqualTo("MISSING_EXACT_TEXT");
        assertThat(plan.getClarificationQuestion()).contains("精确查找");
        verify(delegate, never()).plan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
