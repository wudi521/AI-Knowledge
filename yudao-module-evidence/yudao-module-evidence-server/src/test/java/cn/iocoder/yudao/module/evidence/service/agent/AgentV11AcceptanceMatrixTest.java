package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.AgenticEvidenceFacade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryEngineV3Facade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryRouter;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公共 Agentic Knowledge Runtime 架构级验收矩阵。
 *
 * <p>这里只锁死不能退化的公共协议：确定性答案有 provenance、confidence 不伪造、
 * 澄清/权限/失败都不能被旧 V3 覆盖，旧 mode 配置也不能绕过在线 Runtime。</p>
 */
class AgentV11AcceptanceMatrixTest {

    @Test
    void deterministicEntityAnswerMustExposeStructuredProvenanceWithoutFakeConfidence() {
        AgenticKnowledgeRuntimeEngine engine = mock(AgenticKnowledgeRuntimeEngine.class);
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        AgenticEvidenceFacade facade = new AgenticEvidenceFacade(engine, recorder);

        AgenticKnowledgeRuntimeEngine.Result result = new AgenticKnowledgeRuntimeEngine.Result(
                AgenticKnowledgeRuntimeEngine.State.ANSWER,
                "公布号=CN123",
                null,
                AgentStopReason.ENOUGH_EVIDENCE,
                List.of(),
                1,
                2,
                EvidenceCoverage.FULL,
                null,
                List.of(new AgentTraceStep(1, "ANSWER_VALIDATION", "ANSWER", null,
                        "回答 immutable OriginalGoal", "SUCCEEDED", 0L,
                        "deterministic references satisfy immutable OriginalGoal",
                        AgentStopReason.ENOUGH_EVIDENCE)),
                List.of(74L),
                List.of(), List.of(), List.of());
        when(engine.execute(eq("申请号X的公布号是什么？"), eq(6L), eq("PATENT"), eq(1L), eq(2L),
                any(), anyList(), anyList())).thenReturn(result);

        EvidenceEvaluateRespVO resp = facade.evaluateUnrecorded(
                "申请号X的公布号是什么？", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-1");

        assertTrue(Boolean.TRUE.equals(resp.getAnswerable()));
        assertEquals("公布号=CN123", resp.getAnswer());
        assertNull(resp.getConfidence(), "未校准连续置信度前必须保持 null");
        assertEquals(1, resp.getEvidence().size());
        assertEquals("STRUCTURED_RESULT", resp.getEvidence().get(0).getEvidenceType());
        assertEquals("公布号=CN123", resp.getEvidence().get(0).getContent());
        assertEquals(74L, resp.getEvidence().get(0).getDocumentId());
        assertTrue(resp.getEvidence().get(0).getFilters().contains("verifiedEntityIds=[74]"));
        assertEquals(List.of(74L), resp.getStructuredResult().getEntityIds());
        assertEquals("ENOUGH_EVIDENCE", resp.getReasonCode());
        assertEquals("AGENTIC_KNOWLEDGE_RUNTIME", resp.getExecutionMode());
    }

    @Test
    void clarificationMustNeverFallBackToV3() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setMode("AGENT_WITH_V3_FALLBACK");
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, properties);

        EvidenceEvaluateRespVO clarify = response(false, "NEED_USER_INPUT", "ag-contract-2");
        clarify.setClarifyQuestion("你说的“这个专利”具体指哪一件？");
        when(agent.evaluateUnrecorded("这个专利的发明人是谁？", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-2")).thenReturn(clarify);

        EvidenceEvaluateRespVO actual = router.evaluate("这个专利的发明人是谁？", List.of(6L), 8,
                1L, 2L, List.of(), false, "ag-contract-2", "PATENT", null, null);

        assertSame(clarify, actual);
        verify(agent).record(clarify);
        verify(v3, never()).evaluate(any(), anyList(), any(), any(), any(), anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void permissionFailureMustNeverFallBackToV3() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setMode("AGENT_WITH_V3_FALLBACK");
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, properties);

        EvidenceEvaluateRespVO denied = response(false, "PERMISSION_DENIED", "ag-contract-3");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-3")).thenReturn(denied);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "ag-contract-3", "PATENT", null, null);

        assertSame(denied, actual);
        verify(agent).record(denied);
        verify(v3, never()).evaluate(any(), anyList(), any(), any(), any(), anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyV3ModeMustNotBypassPublicRuntime() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setMode("V3");
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, properties);

        EvidenceEvaluateRespVO agentResp = response(false, "CAPABILITY_UNAVAILABLE", "trace-v3-config");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-v3-config")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-v3-config", "PATENT", null, null);

        assertSame(agentResp, actual);
        assertEquals("AGENT", router.mode());
        verify(agent).record(agentResp);
        verify(v3, never()).evaluate(any(), anyList(), any(), any(), any(), anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void singleKbBoundaryMustFailClosedInPublicRuntime() {
        AgenticKnowledgeRuntimeEngine engine = mock(AgenticKnowledgeRuntimeEngine.class);
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        AgenticEvidenceFacade facade = new AgenticEvidenceFacade(engine, recorder);

        EvidenceEvaluateRespVO resp = facade.evaluateUnrecorded(
                "跨两个知识库统计专利", List.of(6L, 7L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-4");

        assertFalse(Boolean.TRUE.equals(resp.getAnswerable()));
        assertEquals("AGENT_SINGLE_KB_REQUIRED", resp.getReasonCode());
        assertNull(resp.getConfidence());
        assertEquals("AGENTIC_KNOWLEDGE_RUNTIME", resp.getExecutionMode());
        verify(engine, never()).execute(any(), any(), any(), any(), any(), any(), anyList(), anyList());
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
