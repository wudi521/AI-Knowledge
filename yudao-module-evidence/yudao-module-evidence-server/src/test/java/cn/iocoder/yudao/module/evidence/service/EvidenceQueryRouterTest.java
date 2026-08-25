package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueryRouterTest {

    @Test
    void onlineRouterOnlyDependsOnAgentFacade() {
        Constructor<?>[] constructors = EvidenceQueryRouter.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(1, constructors[0].getParameterCount());
        assertEquals(AgenticEvidenceFacade.class, constructors[0].getParameterTypes()[0]);
        assertTrue(java.util.Arrays.stream(constructors[0].getParameterTypes())
                .noneMatch(type -> type.getName().contains("V3")));
    }

    @Test
    void runtimeFailureCannotEscapeIntoLegacyFallback() {
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(agent);

        EvidenceEvaluateRespVO agentResp = response(false, "CAPABILITY_UNAVAILABLE", "trace-1");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-1")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-1", "PATENT", null, null);

        assertSame(agentResp, actual);
        assertEquals("AGENT", router.mode());
        verify(agent).record(agentResp);
    }

    @Test
    void evidenceInsufficiencyStaysOnAgentRuntime() {
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(agent);

        EvidenceEvaluateRespVO agentResp = response(false, "NO_RELIABLE_EVIDENCE", "trace-2");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-2")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-2", "PATENT", null, null);
        assertSame(agentResp, actual);
        verify(agent).record(agentResp);
    }

    private EvidenceEvaluateRespVO response(boolean answerable, String reasonCode, String traceId) {
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setAnswerable(answerable);
        resp.setReasonCode(reasonCode);
        resp.setStages(List.of());
        resp.setEvidence(List.of());
        resp.setConflicts(List.of());
        return resp;
    }
}
