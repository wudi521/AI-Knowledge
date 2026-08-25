package cn.iocoder.yudao.module.knowledge.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionStateTest {

    @Test
    void originalGoalMustRemainUnchangedWhenSubGoalChanges() {
        AgentExecutionState state = new AgentExecutionState("现在专利库里面有名称相近的专利吗？");

        state.setCurrentSubGoal("读取当前知识库中的专利标题");
        state.setCurrentSubGoal("比较候选标题之间的相似程度");

        assertEquals("现在专利库里面有名称相近的专利吗？", state.getOriginalGoal());
        assertEquals("比较候选标题之间的相似程度", state.getCurrentSubGoal());
    }

    @Test
    void shouldDetectRepeatedCapabilityCall() {
        AgentExecutionState state = new AgentExecutionState("测试问题");
        AgentExecutionGuard guard = new AgentExecutionGuard(new AgentExecutionBudget(3, 3, 10_000));

        assertTrue(guard.beforeCapabilityCall(state, "same-call").allowed());
        state.addCapabilityCallFingerprint("same-call");
        AgentExecutionGuard.GuardResult result = guard.beforeCapabilityCall(state, "same-call");

        assertFalse(result.allowed());
        assertEquals(AgentStopReason.REPEATED_CALL, result.stopReason());
    }
}
