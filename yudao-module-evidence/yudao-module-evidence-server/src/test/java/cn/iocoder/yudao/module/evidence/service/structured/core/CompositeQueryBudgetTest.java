package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeQueryBudgetTest {

    @Test
    void defaultsPreventUnboundedFanoutAndAgentLoops() {
        CompositeQueryPlan.Budget b = CompositeQueryPlan.Budget.defaults();
        assertThat(b.maxSteps()).isEqualTo(5);
        assertThat(b.maxEntities()).isEqualTo(10);
        assertThat(b.maxModelCalls()).isEqualTo(2);
        assertThat(b.deadlineMs()).isEqualTo(20_000L);
    }

    @Test
    void callerOverridesAreClampedToServerSafetyLimits() {
        QueryPlanBudgetDTO dto = new QueryPlanBudgetDTO();
        dto.setMaxSteps(100);
        dto.setMaxEntities(10_000);
        dto.setMaxModelCalls(100);
        dto.setDeadlineMs(600_000L);

        CompositeQueryPlan.Budget b = CompositeQueryPlan.Budget.of(dto);

        assertThat(b.maxSteps()).isEqualTo(8);
        assertThat(b.maxEntities()).isEqualTo(50);
        assertThat(b.maxModelCalls()).isEqualTo(4);
        assertThat(b.deadlineMs()).isEqualTo(60_000L);
    }

    @Test
    void invalidOverridesFallBackToSafeDefaults() {
        QueryPlanBudgetDTO dto = new QueryPlanBudgetDTO();
        dto.setMaxSteps(0);
        dto.setMaxEntities(-1);
        dto.setMaxModelCalls(0);
        dto.setDeadlineMs(0L);

        assertThat(CompositeQueryPlan.Budget.of(dto)).isEqualTo(CompositeQueryPlan.Budget.defaults());
    }
}
