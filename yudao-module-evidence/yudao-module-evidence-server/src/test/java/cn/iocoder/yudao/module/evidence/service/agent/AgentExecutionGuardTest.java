package cn.iocoder.yudao.module.evidence.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionGuardTest {

    @Test
    void repeatedCapabilityFingerprintMustBeRejected() {
        AgentExecutionState state = new AgentExecutionState("原始问题");
        AgentExecutionGuard guard = new AgentExecutionGuard(new AgentExecutionBudget(6, 6, 15_000));
        assertTrue(guard.beforeCapabilityCall(state, "same").allowed());
        state.addCapabilityCallFingerprint("same");
        AgentExecutionGuard.GuardResult result = guard.beforeCapabilityCall(state, "same");
        assertFalse(result.allowed());
        assertEquals(AgentStopReason.REPEATED_CALL, result.stopReason());
    }

    @Test
    void stepAndLlmBudgetsMustBeHardLimits() {
        AgentExecutionState state = new AgentExecutionState("原始问题");
        AgentExecutionGuard guard = new AgentExecutionGuard(new AgentExecutionBudget(1, 1, 15_000));

        state.incrementLlmCalls();
        AgentExecutionGuard.GuardResult planner = guard.beforePlannerCall(state);
        assertFalse(planner.allowed());
        assertEquals(AgentStopReason.MAX_LLM_CALLS, planner.stopReason());

        AgentExecutionState stepState = new AgentExecutionState("原始问题");
        stepState.incrementStep();
        AgentExecutionGuard.GuardResult capability = guard.beforeCapabilityCall(stepState, "new");
        assertFalse(capability.allowed());
        assertEquals(AgentStopReason.MAX_STEPS, capability.stopReason());
    }
}
