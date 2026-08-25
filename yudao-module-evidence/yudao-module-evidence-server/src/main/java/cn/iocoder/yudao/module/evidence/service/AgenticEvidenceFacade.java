package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgenticQueryEngine;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** V1.1 独立评估入口；当前不替换 V3 正式入口。 */
@Service
public class AgenticEvidenceFacade {
    private final AgenticQueryEngine agenticQueryEngine;
    private final EvidenceRecorder recorder;

    public AgenticEvidenceFacade(AgenticQueryEngine agenticQueryEngine, EvidenceRecorder recorder) {
        this.agenticQueryEngine = agenticQueryEngine;
        this.recorder = recorder;
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, String domainCode,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history) {
        long start = System.currentTimeMillis();
        String traceId = newTraceId();
        if (kbIds == null || kbIds.size() != 1) {
            return stopped(query, traceId, "AGENT_SINGLE_KB_REQUIRED", "V1.1 当前仅允许单知识库执行。", start);
        }

        AgenticQueryEngine.Result result = agenticQueryEngine.execute(query, kbIds.get(0), domainCode,
                tenantId, userId, traceId, history == null ? List.of() : history);
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setQuery(query);
        resp.setHistory(history == null ? List.of() : history);
        resp.setConsultable(false);
        resp.setConflicts(List.of());
        resp.setClaimFail(false);
        resp.setExecutionMode("AGENTIC_V1");
        resp.setIntent("AGENTIC_V1");
        resp.setReasonCode(result.stopReason() == null ? null : result.stopReason().name());
        resp.setConfidence(null);

        boolean answer = result.state() == AgenticQueryEngine.State.ANSWER;
        boolean clarify = result.state() == AgenticQueryEngine.State.CLARIFY;
        resp.setAnswerable(answer);
        resp.setAnswer(answer ? result.answer() : null);
        resp.setClarifyQuestion(clarify ? result.clarificationQuestion() : null);
        resp.setRefusalReason(answer ? null : StrUtil.blankToDefault(result.clarificationQuestion(), "当前无法可靠完成该查询。"));
        resp.setRoute(answer ? "AGENTIC_ANSWER" : clarify ? "CLARIFY" : "ABSTAIN");
        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        resp.setEvidence(evidences.stream().map(e -> toEvidence(e, kbIds.get(0), domainCode)).toList());
        resp.setStages(List.of(stage(result, System.currentTimeMillis() - start)));
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        recorder.record(resp, evidences, List.of());
        return resp;
    }

    private EvidenceEvaluateRespVO stopped(String query, String traceId, String reasonCode,
                                           String reason, long start) {
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setQuery(query);
        resp.setAnswerable(false);
        resp.setConfidence(null);
        resp.setConsultable(false);
        resp.setRefusalReason(reason);
        resp.setRoute("ABSTAIN");
        resp.setIntent("AGENTIC_V1");
        resp.setExecutionMode("AGENTIC_V1");
        resp.setReasonCode(reasonCode);
        resp.setEvidence(List.of());
        resp.setConflicts(List.of());
        resp.setClaimFail(false);
        resp.setStages(List.of());
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        return resp;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO toEvidence(Evidence evidence, Long kbId, String domainCode) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceId(evidence.getChunkId());
        vo.setChunkId(evidence.getChunkId());
        vo.setContent(evidence.getContent());
        vo.setChunkMetadata(evidence.getChunkMetadata());
        vo.setDocumentName(evidence.getDocumentName());
        vo.setVersionNo(evidence.getVersionNo());
        vo.setVersionId(evidence.getVersionId());
        vo.setDocumentId(parseLong(evidence.getDocumentId()));
        vo.setKbId(kbId);
        vo.setDomainCode(domainCode);
        vo.setScore(evidence.getScore());
        vo.setEvidenceType("CHUNK");
        vo.setChannels(evidence.getChannels() == null ? List.of() : new ArrayList<>(evidence.getChannels()));
        return vo;
    }

    private QueryStageTimingDTO stage(AgenticQueryEngine.Result result, long elapsedMs) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage("AGENTIC_V1_EXECUTION");
        dto.setSeq(1);
        dto.setStatus("SUCCEEDED");
        dto.setSkipped(false);
        dto.setElapsedMs(elapsedMs);
        dto.setInputSummary("bounded capability loop");
        dto.setOutputSummary("state=" + result.state() + "; steps=" + result.steps() + "; llmCalls=" + result.llmCalls()
                + "; evidenceCoverage=" + result.evidenceCoverage() + "; stopReason=" + result.stopReason());
        return dto;
    }

    private Long parseLong(String value) {
        try { return StrUtil.isBlank(value) ? null : Long.parseLong(value); }
        catch (Exception e) { return null; }
    }

    private String newTraceId() {
        return "ag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
