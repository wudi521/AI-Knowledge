package cn.iocoder.yudao.module.evidence.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceAnalysisDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceChannelStatDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceConflictDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceSlotValueDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.AgenticEvidenceFacade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryEngineV3Facade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryScopeResolver;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 证据平台 RPC。支持 V3、Agent V1.1 和受控 Agent->V3 fallback。 */
@Slf4j
@RestController
@Validated
public class EvidenceApiImpl implements EvidenceApi {
    private static final Set<String> SAFE_FALLBACK_REASONS = Set.of(
            "CAPABILITY_UNAVAILABLE", "MAX_STEPS", "MAX_LLM_CALLS", "TIME_BUDGET_EXCEEDED",
            "REPEATED_CALL", "NO_PROGRESS", "INVALID_CAPABILITY_CALL", "AGENT_SINGLE_KB_REQUIRED");

    private final EvidenceQueryEngineV3Facade queryEngineV3Facade;
    private final AgenticEvidenceFacade agenticEvidenceFacade;
    private final EvidenceQueryScopeResolver queryScopeResolver;
    private final EvidenceProperties properties;

    public EvidenceApiImpl(EvidenceQueryEngineV3Facade queryEngineV3Facade,
                           AgenticEvidenceFacade agenticEvidenceFacade,
                           EvidenceQueryScopeResolver queryScopeResolver,
                           EvidenceProperties properties) {
        this.queryEngineV3Facade = queryEngineV3Facade;
        this.agenticEvidenceFacade = agenticEvidenceFacade;
        this.queryScopeResolver = queryScopeResolver;
        this.properties = properties;
    }

    @Override
    public CommonResult<EvidenceEvaluateRespDTO> evaluate(EvidenceEvaluateReqDTO req) {
        if (req == null) return success(denied(null, "INVALID_REQUEST", "请求不能为空"));
        EvidenceQueryScopeResolver.Resolution scope = queryScopeResolver.resolve(req.getKbIds(), req.getUserId(), req.getDomainCode());
        if (!scope.allowed()) return success(denied(req, scope.reasonCode(), scope.message()));

        String mode = agentMode();
        if (!"V3".equals(mode)) {
            EvidenceEvaluateRespVO agent = agenticEvidenceFacade.evaluateUnrecorded(req.getQuery(), scope.kbIds(), scope.domainCode(),
                    req.getTenantId(), req.getUserId(), req.getHistory(), req.getContextResolutionJson(), req.getTraceId());
            if ("AGENT".equals(mode) || Boolean.TRUE.equals(agent.getAnswerable())
                    || "NEED_USER_INPUT".equals(agent.getReasonCode()) || !shouldFallback(agent.getReasonCode())) {
                agenticEvidenceFacade.record(agent);
                return success(toDto(agent));
            }
            EvidenceEvaluateRespVO v3 = queryEngineV3Facade.evaluate(req.getQuery(), scope.kbIds(), req.getTopK(),
                    req.getTenantId(), req.getUserId(), req.getHistory(), req.getSkipSlotDetection(), agent.getTraceId(),
                    scope.domainCode(), req.getContextResolutionJson(), req.getPlanBudget());
            mergeFallbackStages(agent, v3);
            return success(toDto(v3));
        }

        EvidenceEvaluateRespVO vo = queryEngineV3Facade.evaluate(req.getQuery(), scope.kbIds(), req.getTopK(),
                req.getTenantId(), req.getUserId(), req.getHistory(), req.getSkipSlotDetection(), req.getTraceId(),
                scope.domainCode(), req.getContextResolutionJson(), req.getPlanBudget());
        return success(toDto(vo));
    }

    private String agentMode() {
        String mode = properties == null || properties.getAgent() == null ? "V3" : properties.getAgent().getMode();
        if (mode == null) return "V3";
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return Set.of("V3", "AGENT", "AGENT_WITH_V3_FALLBACK").contains(normalized) ? normalized : "V3";
    }

    private boolean shouldFallback(String reasonCode) { return reasonCode != null && SAFE_FALLBACK_REASONS.contains(reasonCode); }

    private void mergeFallbackStages(EvidenceEvaluateRespVO agent, EvidenceEvaluateRespVO v3) {
        List<QueryStageTimingDTO> merged = new ArrayList<>();
        if (agent.getStages() != null) merged.addAll(agent.getStages());
        QueryStageTimingDTO fallback = new QueryStageTimingDTO();
        fallback.setStage("AGENT_FALLBACK_TO_V3"); fallback.setStatus("SUCCEEDED"); fallback.setSkipped(false); fallback.setElapsedMs(0L);
        fallback.setInputSummary("agentReason=" + agent.getReasonCode());
        fallback.setOutputSummary("fallback to Query Engine V3");
        merged.add(fallback);
        if (v3.getStages() != null) merged.addAll(v3.getStages());
        for (int i = 0; i < merged.size(); i++) merged.get(i).setSeq(i + 1);
        v3.setStages(merged);
        v3.setExecutionMode("AGENTIC_V1_FALLBACK_V3");
    }

    private EvidenceEvaluateRespDTO denied(EvidenceEvaluateReqDTO req, String reasonCode, String message) {
        EvidenceEvaluateRespDTO dto = new EvidenceEvaluateRespDTO();
        dto.setQuery(req != null ? req.getQuery() : null); dto.setAnswerable(false); dto.setConfidence(0D);
        dto.setConsultable(false); dto.setRefusalReason(message); dto.setEvidence(List.of()); dto.setConflicts(List.of());
        dto.setClaims(List.of()); dto.setClaimFail(false); dto.setRoute("ABSTAIN"); dto.setIntent("SCOPE_REJECTED");
        dto.setExecutionMode(null); dto.setReasonCode(reasonCode); dto.setHistory(req != null ? req.getHistory() : null); dto.setElapsedMs(0);
        return dto;
    }

    private EvidenceEvaluateRespDTO toDto(EvidenceEvaluateRespVO vo) {
        EvidenceEvaluateRespDTO dto = new EvidenceEvaluateRespDTO();
        dto.setTraceId(vo.getTraceId()); dto.setQuery(vo.getQuery()); dto.setAnswerable(vo.getAnswerable()); dto.setConfidence(vo.getConfidence());
        dto.setConsultable(vo.getConsultable()); dto.setRefusalReason(vo.getRefusalReason()); dto.setAnswer(vo.getAnswer());
        dto.setClaimFail(vo.getClaimFail()); dto.setVerificationDegraded(vo.getVerificationDegraded()); dto.setTimedOut(vo.getTimedOut());
        dto.setStages(vo.getStages()); dto.setElapsedMs(vo.getElapsedMs());

        List<EvidenceItemDTO> evidence = new ArrayList<>();
        if (vo.getEvidence() != null) for (EvidenceEvaluateRespVO.EvidenceItemVO item : vo.getEvidence()) {
            EvidenceItemDTO d = new EvidenceItemDTO();
            d.setEvidenceId(item.getEvidenceId()); d.setChunkId(item.getChunkId()); d.setContent(item.getContent());
            d.setDocumentName(item.getDocumentName()); d.setVersionNo(item.getVersionNo()); d.setVersionId(item.getVersionId());
            d.setDocumentId(item.getDocumentId()); d.setKbId(item.getKbId()); d.setDomainCode(item.getDomainCode());
            d.setSectionType(item.getSectionType()); d.setSectionTitle(item.getSectionTitle()); d.setClaimNo(item.getClaimNo());
            d.setPageStart(item.getPageStart()); d.setPageEnd(item.getPageEnd()); d.setApplicationNo(item.getApplicationNo());
            d.setPublicationNo(item.getPublicationNo()); d.setScore(item.getScore()); d.setEvidenceType(item.getEvidenceType());
            d.setMetric(item.getMetric()); d.setAggregateValue(item.getAggregateValue()); d.setFilters(item.getFilters());
            d.setChannels(item.getChannels() != null ? new ArrayList<>(item.getChannels()) : null); d.setChunkMetadata(item.getChunkMetadata());
            evidence.add(d);
        }
        dto.setEvidence(evidence);

        List<EvidenceConflictDTO> conflicts = new ArrayList<>();
        if (vo.getConflicts() != null) for (EvidenceEvaluateRespVO.ConflictVO c : vo.getConflicts()) {
            EvidenceConflictDTO d = new EvidenceConflictDTO(); d.setEvidenceIndexA(c.getEvidenceIndexA());
            d.setEvidenceIndexB(c.getEvidenceIndexB()); d.setReason(c.getReason()); conflicts.add(d);
        }
        dto.setConflicts(conflicts);

        List<EvidenceClaimDTO> claims = new ArrayList<>();
        if (vo.getClaims() != null) for (EvidenceEvaluateRespVO.ClaimVO c : vo.getClaims()) {
            EvidenceClaimDTO d = new EvidenceClaimDTO(); d.setText(c.getText()); d.setVerdict(c.getVerdict());
            d.setEvidenceIndex(c.getEvidenceIndex()); claims.add(d);
        }
        dto.setClaims(claims);
        dto.setSlotKbId(vo.getSlotKbId()); dto.setExtractedSlots(toSlotValueDTOList(vo.getExtractedSlots()));
        dto.setMissingSlots(toSlotValueDTOList(vo.getMissingSlots())); dto.setClarifyQuestion(vo.getClarifyQuestion());
        dto.setAnalysis(toAnalysisDTO(vo.getAnalysis())); dto.setChannels(toChannelStatDTO(vo.getChannels()));
        dto.setRoute(vo.getRoute()); dto.setIntent(vo.getIntent()); dto.setHistory(vo.getHistory());
        dto.setStructuredResult(vo.getStructuredResult()); dto.setExecutionMode(vo.getExecutionMode()); dto.setReasonCode(vo.getReasonCode());
        return dto;
    }

    private List<EvidenceSlotValueDTO> toSlotValueDTOList(List<EvidenceEvaluateRespVO.SlotValueVO> list) {
        List<EvidenceSlotValueDTO> result = new ArrayList<>();
        if (list != null) for (EvidenceEvaluateRespVO.SlotValueVO vo : list) {
            EvidenceSlotValueDTO d = new EvidenceSlotValueDTO(); d.setCode(vo.getCode()); d.setName(vo.getName()); d.setValue(vo.getValue()); result.add(d);
        }
        return result;
    }

    private EvidenceAnalysisDTO toAnalysisDTO(RetrievalSearchRespDTO.RetrievalAnalysisDTO vo) {
        if (vo == null) return null;
        EvidenceAnalysisDTO dto = new EvidenceAnalysisDTO(); dto.setIntent(vo.getIntent()); dto.setEntities(vo.getEntities());
        dto.setRewrites(vo.getRewrites()); dto.setSubQuestions(vo.getSubQuestions()); dto.setSuccess(vo.getSuccess()); dto.setRoute(vo.getRoute());
        return dto;
    }

    private EvidenceChannelStatDTO toChannelStatDTO(RetrievalSearchRespDTO.RetrievalChannelStatDTO vo) {
        if (vo == null) return null;
        EvidenceChannelStatDTO dto = new EvidenceChannelStatDTO(); dto.setBm25(vo.getBm25()); dto.setVector(vo.getVector()); dto.setFused(vo.getFused());
        return dto;
    }
}
