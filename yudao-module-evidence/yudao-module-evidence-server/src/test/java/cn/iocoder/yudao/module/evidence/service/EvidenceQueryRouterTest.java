package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueryRouterTest {

    @Test
    void legacyFallbackConfigurationMustNotBypassAgentRuntime() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setMode("AGENT_WITH_V3_FALLBACK");
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, properties);

        EvidenceEvaluateRespVO agentResp = response(false, "CAPABILITY_UNAVAILABLE", "trace-1");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-1")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-1", "PATENT", null, null);

        assertSame(agentResp, actual);
        assertEquals("AGENT", router.mode());
        verify(agent).record(agentResp);
        verify(v3, never()).evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-1", "PATENT", null, null);
    }

    @Test
    void evidenceInsufficiencyStaysOnAgentRuntime() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, new EvidenceProperties());

        EvidenceEvaluateRespVO agentResp = response(false, "NO_RELIABLE_EVIDENCE", "trace-2");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-2")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-2", "PATENT", null, null);
        assertSame(agentResp, actual);
        verify(agent).record(agentResp);
        verify(v3, never()).evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-2", "PATENT", null, null);
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
