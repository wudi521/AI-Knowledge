package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.agent.AgentTraceStep;
import cn.iocoder.yudao.module.evidence.service.agent.AgenticKnowledgeRuntimeEngine;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ProvenanceRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ReferenceRecord;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredContextHint;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public Agentic Knowledge Runtime facade and trace recorder. */
@Service
public class AgenticEvidenceFacade {
    private final AgenticKnowledgeRuntimeEngine runtimeEngine;
    private final EvidenceRecorder recorder;

    public AgenticEvidenceFacade(AgenticKnowledgeRuntimeEngine runtimeEngine, EvidenceRecorder recorder) {
        this.runtimeEngine = runtimeEngine;
        this.recorder = recorder;
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, String domainCode,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history) {
        EvidenceEvaluateRespVO resp = evaluateUnrecorded(query, kbIds, domainCode, tenantId, userId,
                history, null, null);
        record(resp);
        return resp;
    }

    public EvidenceEvaluateRespVO evaluateUnrecorded(String query, List<Long> kbIds, String domainCode,
                                                      Long tenantId, Long userId, List<ChatTurnDTO> history,
                                                      String contextResolutionJson, String incomingTraceId) {
        long start = System.currentTimeMillis();
        String traceId = StrUtil.isNotBlank(incomingTraceId) ? incomingTraceId : newTraceId();
        if (kbIds == null || kbIds.size() != 1) {
            return stopped(query, traceId, "AGENT_SINGLE_KB_REQUIRED", "当前公共 Runtime 仅允许单知识库执行。", start);
        }
        List<ChatTurnDTO> safeHistory = history == null ? List.of() : history;
        List<Long> contextEntityIds = contextEntityIds(contextResolutionJson);
        AgenticKnowledgeRuntimeEngine.Result result = runtimeEngine.execute(query, kbIds.get(0), domainCode,
                tenantId, userId, traceId, safeHistory, contextEntityIds);

        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setQuery(query);
        resp.setHistory(safeHistory);
        resp.setConsultable(false);
        resp.setConflicts(List.of());
        resp.setExecutionMode("AGENTIC_KNOWLEDGE_RUNTIME");
        resp.setIntent("AGENTIC_RUNTIME");
        resp.setReasonCode(result.stopReason() == null ? null : result.stopReason().name());
        resp.setConfidence(null);

        boolean answer = result.state() == AgenticKnowledgeRuntimeEngine.State.ANSWER;
        boolean clarify = result.state() == AgenticKnowledgeRuntimeEngine.State.CLARIFY;
        resp.setAnswerable(answer);
        resp.setAnswer(answer ? result.answer() : null);
        resp.setClarifyQuestion(clarify ? result.clarificationQuestion() : null);
        resp.setRefusalReason(answer ? null : StrUtil.blankToDefault(result.clarificationQuestion(), "当前无法可靠完成该查询。"));
        resp.setRoute(answer ? "AGENTIC_ANSWER" : clarify ? "CLARIFY" : "ABSTAIN");

        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        List<EvidenceEvaluateRespVO.EvidenceItemVO> evidenceItems = evidences.stream()
                .map(e -> toEvidence(e, kbIds.get(0), domainCode))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (answer && evidenceItems.isEmpty()) {
            evidenceItems.add(structuredEvidence(result.answer(), kbIds.get(0), domainCode, result.verifiedEntityIds()));
        }
        resp.setEvidence(evidenceItems);
        resp.setStructuredResult(structuredResult(result));
        applyGeneration(resp, result.generation());
        // QueryTraceStageStore 会持久化这些阶段，因此 Reference / Provenance 也能随 traceId 回放。
        resp.setStages(stages(result));
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        return resp;
    }

    public void record(EvidenceEvaluateRespVO resp) {
        if (resp == null) return;
        List<Evidence> evidences = new ArrayList<>();
        if (resp.getEvidence() != null) {
            for (EvidenceEvaluateRespVO.EvidenceItemVO item : resp.getEvidence()) {
                if (item == null || !"CHUNK".equals(item.getEvidenceType())) continue;
                evidences.add(Evidence.builder()
                        .chunkId(item.getChunkId())
                        .content(item.getContent())
                        .documentId(item.getDocumentId() == null ? null : String.valueOf(item.getDocumentId()))
                        .documentName(item.getDocumentName())
                        .versionNo(item.getVersionNo())
                        .versionId(item.getVersionId())
                        .score(item.getScore())
                        .products(List.of())
                        .channels(item.getChannels() == null ? new ArrayList<>() : new ArrayList<>(item.getChannels()))
                        .chunkMetadata(item.getChunkMetadata())
                        .build());
            }
        }
        recorder.record(resp, evidences, List.of());
    }

    /** Returns persisted planning/runtime/evaluation/reference/provenance/answer steps for a traceId. */
    public List<QueryStageTimingDTO> replayTrace(String traceId) {
        return recorder.findStages(traceId);
    }

    private void applyGeneration(EvidenceEvaluateRespVO resp, GenerationResult generation) {
        resp.setClaimFail(generation != null && generation.isClaimFail());
        if (generation == null) return;
        resp.setVerificationDegraded(generation.isVerificationDegraded());
        resp.setTimedOut(generation.isTimedOut());
        if (generation.getClaims() != null) {
            resp.setClaims(generation.getClaims().stream().map(this::toClaim).toList());
        }
    }

    private EvidenceEvaluateRespVO.ClaimVO toClaim(ClaimResult claim) {
        EvidenceEvaluateRespVO.ClaimVO vo = new EvidenceEvaluateRespVO.ClaimVO();
        vo.setText(claim.getText());
        vo.setVerdict(claim.getVerdict());
        vo.setEvidenceIndex(claim.getEvidenceIndex());
        return vo;
    }

    private StructuredResultDTO structuredResult(AgenticKnowledgeRuntimeEngine.Result result) {
        if (result == null || result.verifiedEntityIds() == null || result.verifiedEntityIds().isEmpty()) return null;
        StructuredResultDTO dto = new StructuredResultDTO();
        dto.setEntityIds(List.copyOf(result.verifiedEntityIds()));
        dto.setEntityCount(result.verifiedEntityIds().size());
        dto.setTruncated(false);
        dto.setScopeType("VERIFIED_ENTITY_SET");
        dto.setQueryType("AGENT_RUNTIME_VERIFIED_SET");
        return dto;
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
        resp.setIntent("AGENTIC_RUNTIME");
        resp.setExecutionMode("AGENTIC_KNOWLEDGE_RUNTIME");
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
        fillMetadata(vo, evidence.getChunkMetadata());
        return vo;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO structuredEvidence(String answer, Long kbId, String domainCode,
                                                                      List<Long> verifiedEntityIds) {
        List<Long> entityIds = verifiedEntityIds == null ? List.of() : verifiedEntityIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceType("STRUCTURED_RESULT");
        vo.setKbId(kbId);
        vo.setDomainCode(domainCode);
        vo.setContent(answer);
        vo.setScore(1D);
        if (entityIds.size() == 1) vo.setDocumentId(entityIds.get(0));
        vo.setFilters("agenticRuntime=true,evidenceCoverage=FULL,verifiedEntityIds=" + entityIds);
        return vo;
    }

    private List<QueryStageTimingDTO> stages(AgenticKnowledgeRuntimeEngine.Result result) {
        if (result == null) return List.of();
        List<QueryStageTimingDTO> out = new ArrayList<>();
        if (result.traceSteps() != null) {
            for (AgentTraceStep step : result.traceSteps()) out.add(stage(step));
        }

        Map<String, ProvenanceRecord> provenanceByReference = new HashMap<>();
        if (result.provenance() != null) {
            for (ProvenanceRecord record : result.provenance()) {
                if (record != null && StrUtil.isNotBlank(record.referenceId())) {
                    provenanceByReference.put(record.referenceId(), record);
                }
            }
        }
        int seq = out.size() + 1;
        if (result.references() != null) {
            for (ReferenceRecord reference : result.references()) {
                if (reference == null) continue;
                QueryStageTimingDTO refStage = new QueryStageTimingDTO();
                refStage.setStage("AGENT_REFERENCE_RECORD");
                refStage.setSeq(seq++);
                refStage.setStatus(reference.status().name());
                refStage.setSkipped(false);
                refStage.setElapsedMs(0L);
                refStage.setInputSummary(StrUtil.maxLength("planId=" + reference.planId()
                        + "; nodeId=" + reference.nodeId()
                        + "; capability=" + reference.capability(), 900));
                refStage.setOutputSummary(StrUtil.maxLength("referenceId=" + reference.referenceId()
                        + "; verifiedEntityIds=" + reference.verifiedEntityIds()
                        + "; evidenceCount=" + reference.evidences().size()
                        + "; deterministic=" + StrUtil.maxLength(reference.deterministicAnswer(), 220), 600));
                out.add(refStage);

                ProvenanceRecord provenance = provenanceByReference.get(reference.referenceId());
                if (provenance != null) {
                    QueryStageTimingDTO provenanceStage = new QueryStageTimingDTO();
                    provenanceStage.setStage("AGENT_PROVENANCE_RECORD");
                    provenanceStage.setSeq(seq++);
                    provenanceStage.setStatus("SUCCEEDED");
                    provenanceStage.setSkipped(false);
                    provenanceStage.setElapsedMs(0L);
                    provenanceStage.setInputSummary(StrUtil.maxLength("referenceId=" + provenance.referenceId()
                            + "; planId=" + provenance.planId()
                            + "; nodeId=" + provenance.nodeId()
                            + "; capability=" + provenance.capability(), 900));
                    // 不把 userId/权限/完整参数写入可回放 trace，只保留事实来源所需的非敏感 scope。
                    provenanceStage.setOutputSummary(StrUtil.maxLength("kbId=" + provenance.kbId()
                            + "; domainCode=" + provenance.domainCode()
                            + "; traceId=" + provenance.traceId(), 600));
                    out.add(provenanceStage);
                }
            }
        }
        return List.copyOf(out);
    }

    private QueryStageTimingDTO stage(AgentTraceStep step) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage("AGENT_" + step.phase());
        dto.setSeq(step.seq());
        dto.setStatus(step.status());
        dto.setSkipped(false);
        dto.setElapsedMs(step.elapsedMs());
        dto.setErrorCode(step.stopReason() == null ? null : step.stopReason().name());
        String input = "action=" + StrUtil.nullToEmpty(step.action())
                + "; capability=" + StrUtil.nullToEmpty(step.capability())
                + "; purpose=" + StrUtil.nullToEmpty(step.purpose());
        if (StrUtil.isNotBlank(step.argumentsSummary())) input += "; plan=" + step.argumentsSummary();
        dto.setInputSummary(StrUtil.maxLength(input, 900));
        dto.setOutputSummary(StrUtil.maxLength(step.summary(), 600));
        return dto;
    }

    private List<Long> contextEntityIds(String json) {
        if (StrUtil.isBlank(json)) return List.of();
        try {
            StructuredContextHint hint = JSONUtil.toBean(json, StructuredContextHint.class);
            return hint == null || hint.getExplicitEntityIds() == null ? List.of() : hint.getExplicitEntityIds();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void fillMetadata(EvidenceEvaluateRespVO.EvidenceItemVO vo, String metadata) {
        if (StrUtil.isBlank(metadata)) return;
        try {
            var obj = JSONUtil.parseObj(metadata);
            vo.setApplicationNo(obj.getStr("applicationNo"));
            vo.setPublicationNo(obj.getStr("publicationNo"));
            vo.setSectionType(obj.getStr("sectionType"));
            vo.setSectionTitle(obj.getStr("sectionTitle"));
            vo.setClaimNo(obj.getStr("claimNo"));
            vo.setPageStart(obj.getInt("pageStart"));
            vo.setPageEnd(obj.getInt("pageEnd"));
        } catch (Exception ignore) { }
    }

    private Long parseLong(String value) {
        try { return StrUtil.isBlank(value) ? null : Long.parseLong(value); }
        catch (Exception e) { return null; }
    }

    private String newTraceId() {
        return "ag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
