package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.AgenticEvidenceFacade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryRouter;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ProvenanceRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ReferenceRecord;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

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

/** 公共 Agentic Knowledge Runtime 架构级验收矩阵。 */
class AgentV11AcceptanceMatrixTest {

    @Test
    void deterministicEntityAnswerMustExposeStructuredProvenanceWithoutFakeConfidence() {
        AgenticKnowledgeRuntimeEngine engine = mock(AgenticKnowledgeRuntimeEngine.class);
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        AgenticEvidenceFacade facade = new AgenticEvidenceFacade(engine, recorder);

        ReferenceRecord reference = new ReferenceRecord(
                "ref-1", "plan-1", "n1", "structured_query", CapabilityResultStatus.SUCCESS,
                "申请号X唯一匹配到专利74，并投影公布号CN123", "公布号=CN123",
                List.of(), List.of(74L), Map.of("completeDataset", true, "outputComplete", true));
        ProvenanceRecord provenance = new ProvenanceRecord(
                "ref-1", "plan-1", "n1", "structured_query",
                1L, 2L, 6L, "PATENT", "ag-contract-1", Map.of("source", "structured"));
        AgenticKnowledgeRuntimeEngine.Result result = new AgenticKnowledgeRuntimeEngine.Result(
                AgenticKnowledgeRuntimeEngine.State.ANSWER,
                "公布号=CN123", null, AgentStopReason.ENOUGH_EVIDENCE, List.of(), 1, 2,
                EvidenceCoverage.FULL, null,
                List.of(new AgentTraceStep(1, "ANSWER_VALIDATION", "ANSWER", null,
                        "回答 immutable OriginalGoal", "SUCCEEDED", 0L,
                        "deterministic references satisfy immutable OriginalGoal", AgentStopReason.ENOUGH_EVIDENCE)),
                List.of(74L), List.of(), List.of(reference), List.of(provenance));
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
        assertEquals(74L, resp.getEvidence().get(0).getDocumentId());
        assertTrue(resp.getEvidence().get(0).getFilters().contains("verifiedEntityIds=[74]"));
        assertEquals(List.of(74L), resp.getStructuredResult().getEntityIds());
        assertEquals("ENOUGH_EVIDENCE", resp.getReasonCode());
        assertEquals("AGENTIC_KNOWLEDGE_RUNTIME", resp.getExecutionMode());
        assertTrue(resp.getStages().stream().anyMatch(s -> "AGENT_REFERENCE_RECORD".equals(s.getStage())
                && s.getOutputSummary().contains("referenceId=ref-1")));
        assertTrue(resp.getStages().stream().anyMatch(s -> "AGENT_PROVENANCE_RECORD".equals(s.getStage())
                && s.getOutputSummary().contains("kbId=6")
                && !s.getOutputSummary().contains("userId")));
    }

    @Test
    void onlineRouterConstructorHasNoLegacyEngineDependency() {
        Constructor<?> constructor = EvidenceQueryRouter.class.getDeclaredConstructors()[0];
        assertEquals(1, constructor.getParameterCount());
        assertEquals(AgenticEvidenceFacade.class, constructor.getParameterTypes()[0]);
        for (Class<?> type : constructor.getParameterTypes()) {
            assertFalse(type.getName().contains("V2"));
            assertFalse(type.getName().contains("V3"));
        }
    }

    @Test
    void clarificationStaysOnPublicRuntime() {
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(agent);
        EvidenceEvaluateRespVO clarify = response(false, "NEED_USER_INPUT", "ag-contract-2");
        clarify.setClarifyQuestion("你说的“这个专利”具体指哪一件？");
        when(agent.evaluateUnrecorded("这个专利的发明人是谁？", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-2")).thenReturn(clarify);

        EvidenceEvaluateRespVO actual = router.evaluate("这个专利的发明人是谁？", List.of(6L), 8,
                1L, 2L, List.of(), false, "ag-contract-2", "PATENT", null, null);

        assertSame(clarify, actual);
        verify(agent).record(clarify);
    }

    @Test
    void permissionFailureStaysOnPublicRuntime() {
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(agent);
        EvidenceEvaluateRespVO denied = response(false, "PERMISSION_DENIED", "ag-contract-3");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-3")).thenReturn(denied);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "ag-contract-3", "PATENT", null, null);

        assertSame(denied, actual);
        verify(agent).record(denied);
    }

    @Test
    void runtimeFailureIsNotMaskedByAnyFallback() {
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceQueryRouter router = new EvidenceQueryRouter(agent);
        EvidenceEvaluateRespVO agentResp = response(false, "CAPABILITY_UNAVAILABLE", "trace-agent-only");
        when(agent.evaluateUnrecorded("q", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "trace-agent-only")).thenReturn(agentResp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-agent-only", "PATENT", null, null);

        assertSame(agentResp, actual);
        assertEquals("AGENT", router.mode());
        verify(agent).record(agentResp);
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
