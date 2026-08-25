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
 * V1.1 架构级验收矩阵。
 *
 * <p>这里不测试模型“聪明程度”，只锁死上线时绝不能退化的协议：
 * 确定性答案有 provenance、confidence 不伪造、澄清/权限失败不被旧 V3 覆盖、
 * 迁移模式只有基础设施/能力缺失类错误允许安全回退。</p>
 */
class AgentV11AcceptanceMatrixTest {

    @Test
    void deterministicAnswerMustExposeStructuredProvenanceWithoutFakeConfidence() {
        AgenticQueryEngine engine = mock(AgenticQueryEngine.class);
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        AgenticEvidenceFacade facade = new AgenticEvidenceFacade(engine, recorder);

        AgenticQueryEngine.Result result = new AgenticQueryEngine.Result(
                AgenticQueryEngine.State.ANSWER,
                "当前知识库共有 12 件专利。",
                null,
                AgentStopReason.ENOUGH_EVIDENCE,
                List.of(),
                1,
                2,
                EvidenceCoverage.FULL,
                null,
                List.of(new AgentTraceStep(1, "ANSWER", "ANSWER", null,
                        "回答原始目标", "SUCCEEDED", 0L,
                        "deterministic capability result answered original goal",
                        AgentStopReason.ENOUGH_EVIDENCE)),
                List.of(11L, 12L));
        when(engine.execute(eq("现在专利库有多少专利？"), eq(6L), eq("PATENT"), eq(1L), eq(2L),
                any(), anyList(), anyList())).thenReturn(result);

        EvidenceEvaluateRespVO resp = facade.evaluateUnrecorded(
                "现在专利库有多少专利？", List.of(6L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-1");

        assertTrue(Boolean.TRUE.equals(resp.getAnswerable()));
        assertEquals("当前知识库共有 12 件专利。", resp.getAnswer());
        assertNull(resp.getConfidence(), "V1.1 未校准连续置信度前必须保持 null");
        assertEquals(1, resp.getEvidence().size());
        assertEquals("STRUCTURED_RESULT", resp.getEvidence().get(0).getEvidenceType());
        assertEquals("当前知识库共有 12 件专利。", resp.getEvidence().get(0).getContent());
        assertEquals(List.of(11L, 12L), resp.getStructuredResult().getEntityIds());
        assertEquals("ENOUGH_EVIDENCE", resp.getReasonCode());
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
    void v3ModeMustBypassAgentCompletely() {
        EvidenceQueryEngineV3Facade v3 = mock(EvidenceQueryEngineV3Facade.class);
        AgenticEvidenceFacade agent = mock(AgenticEvidenceFacade.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgent().setMode("V3");
        EvidenceQueryRouter router = new EvidenceQueryRouter(v3, agent, properties);

        EvidenceEvaluateRespVO v3Resp = response(true, null, "trace-v3");
        when(v3.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-v3", "PATENT", null, null)).thenReturn(v3Resp);

        EvidenceEvaluateRespVO actual = router.evaluate("q", List.of(6L), 8, 1L, 2L,
                List.of(), false, "trace-v3", "PATENT", null, null);

        assertSame(v3Resp, actual);
        verify(agent, never()).evaluateUnrecorded(any(), anyList(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void singleKbBoundaryMustFailClosedInPureAgentMode() {
        AgenticQueryEngine engine = mock(AgenticQueryEngine.class);
        EvidenceRecorder recorder = mock(EvidenceRecorder.class);
        AgenticEvidenceFacade facade = new AgenticEvidenceFacade(engine, recorder);

        EvidenceEvaluateRespVO resp = facade.evaluateUnrecorded(
                "跨两个知识库统计专利", List.of(6L, 7L), "PATENT", 1L, 2L,
                List.of(), null, "ag-contract-4");

        assertFalse(Boolean.TRUE.equals(resp.getAnswerable()));
        assertEquals("AGENT_SINGLE_KB_REQUIRED", resp.getReasonCode());
        assertNull(resp.getConfidence());
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
