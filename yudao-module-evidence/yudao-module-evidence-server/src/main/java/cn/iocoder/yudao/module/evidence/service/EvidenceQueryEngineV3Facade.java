package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.planner.v3.QueryEngineV3;
import cn.iocoder.yudao.module.evidence.service.planner.v3.QueryIntentV3;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredContextHint;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evidence/Chat 统一 Query Engine V3 入口。
 *
 * <p>管理端 /evidence/evaluate、Chat RPC、Eval Runner 都应从这里进入，差异仅是 history/context。
 * 旧 EvidenceService 保留为迁移兼容实现，不再作为这三个入口的查询主脑。</p>
 */
@Slf4j
@Service
public class EvidenceQueryEngineV3Facade {

    private final QueryEngineV3 queryEngine;
    private final EvidenceRecorder recorder;

    public EvidenceQueryEngineV3Facade(QueryEngineV3 queryEngine, EvidenceRecorder recorder) {
        this.queryEngine = queryEngine;
        this.recorder = recorder;
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId,
                                           String domainCode, String contextResolutionJson,
                                           QueryPlanBudgetDTO ignoredLegacyBudget) {
        long start = System.currentTimeMillis();
        String traceId = StrUtil.isNotBlank(incomingTraceId) ? incomingTraceId : newTraceId();
        List<Long> explicitEntityIds = contextEntityIds(contextResolutionJson);

        QueryEngineV3.Result result = queryEngine.execute(query, kbIds, domainCode,
                history, explicitEntityIds, tenantId, userId, traceId);
        EvidenceEvaluateRespVO resp = toResponse(query, kbIds, domainCode, history, traceId, result);
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        recorder.record(resp, result != null && result.evidences() != null ? result.evidences() : List.of(), List.of());
        return resp;
    }

    private EvidenceEvaluateRespVO toResponse(String query, List<Long> kbIds, String domainCode,
                                              List<ChatTurnDTO> history, String traceId,
                                              QueryEngineV3.Result result) {
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setQuery(query);
        resp.setHistory(history);
        resp.setConsultable(false);
        resp.setConflicts(List.of());
        resp.setEvidence(List.of());
        resp.setClaimFail(false);

        if (result == null) {
            resp.setAnswerable(false);
            resp.setConfidence(0D);
            resp.setRefusalReason("Query Engine V3 未返回执行结果。");
            resp.setRoute("ABSTAIN");
            resp.setIntent("QUERY_V3_FAILED");
            resp.setExecutionMode("COMPOSITE");
            resp.setReasonCode("EMPTY_ENGINE_RESULT");
            return resp;
        }

        boolean answer = result.state() == QueryEngineV3.State.ANSWER;
        boolean clarify = result.state() == QueryEngineV3.State.CLARIFY;
        resp.setAnswerable(answer);
        resp.setConfidence(answer ? confidence(result) : 0D);
        resp.setAnswer(answer ? result.answer() : null);
        resp.setRefusalReason(answer ? null : clarify
                ? StrUtil.blankToDefault(result.clarificationQuestion(), "需要补充查询条件。")
                : reasonMessage(result));
        resp.setClarifyQuestion(clarify ? result.clarificationQuestion() : null);
        resp.setReasonCode(result.reasonCode());
        resp.setExecutionMode(result.executionMode());
        resp.setRoute(route(result));
        resp.setIntent(intentName(result.intent()));
        resp.setAnalysis(result.analysis());
        resp.setChannels(result.channels());
        resp.setStages(result.stages());
        resp.setStructuredResult(structuredResult(result));

        List<Evidence> evidences = result.evidences() == null ? List.of() : result.evidences();
        resp.setEvidence(evidences.stream().map(e -> toEvidenceItem(e, kbIds, domainCode)).collect(Collectors.toList()));
        applyGeneration(resp, result.generation());
        if (answer && evidences.isEmpty() && result.structuredResult() != null) {
            resp.setEvidence(List.of(structuredEvidence(result, kbIds, domainCode)));
        }
        return resp;
    }

    private void applyGeneration(EvidenceEvaluateRespVO resp, GenerationResult generation) {
        if (generation == null) return;
        resp.setVerificationDegraded(generation.isVerificationDegraded());
        resp.setTimedOut(generation.isTimedOut());
        resp.setClaimFail(generation.isClaimFail());
        if (generation.getClaims() != null) {
            resp.setClaims(generation.getClaims().stream().map(this::toClaim).toList());
        }
        if (generation.isClaimFail()) resp.setAnswer(null);
    }

    private EvidenceEvaluateRespVO.ClaimVO toClaim(ClaimResult claim) {
        EvidenceEvaluateRespVO.ClaimVO vo = new EvidenceEvaluateRespVO.ClaimVO();
        vo.setText(claim.getText());
        vo.setVerdict(claim.getVerdict());
        vo.setEvidenceIndex(claim.getEvidenceIndex());
        return vo;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO toEvidenceItem(Evidence evidence, List<Long> kbIds, String domainCode) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceId(evidence.getChunkId());
        vo.setChunkId(evidence.getChunkId());
        vo.setContent(evidence.getContent());
        vo.setChunkMetadata(evidence.getChunkMetadata());
        vo.setDocumentName(evidence.getDocumentName());
        vo.setVersionNo(evidence.getVersionNo());
        vo.setVersionId(evidence.getVersionId());
        vo.setDocumentId(parseLong(evidence.getDocumentId()));
        vo.setKbId(kbIds != null && kbIds.size() == 1 ? kbIds.get(0) : null);
        vo.setDomainCode(domainCode);
        vo.setScore(evidence.getScore());
        vo.setEvidenceType("CHUNK");
        vo.setChannels(evidence.getChannels() == null ? List.of() : new ArrayList<>(evidence.getChannels()));
        fillMetadata(vo, evidence.getChunkMetadata());
        return vo;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO structuredEvidence(QueryEngineV3.Result result,
                                                                      List<Long> kbIds, String domainCode) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceType("STRUCTURED_RESULT");
        vo.setKbId(kbIds != null && kbIds.size() == 1 ? kbIds.get(0) : null);
        vo.setDomainCode(domainCode);
        vo.setContent(result.answer());
        vo.setScore(1D);
        vo.setFilters("queryEngine=V3,selectionGuarantee=" + result.selectionGuarantee()
                + ",entityIds=" + result.entityIds());
        if (result.structuredResult() != null) {
            vo.setMetric(result.structuredResult().getMetricCode());
            if (result.structuredResult().getValue() != null) {
                double v = result.structuredResult().getValue();
                if (v == Math.floor(v)) vo.setAggregateValue((int) Math.round(v));
            }
        }
        return vo;
    }

    private StructuredResultDTO structuredResult(QueryEngineV3.Result result) {
        if ((result.entityIds() == null || result.entityIds().isEmpty()) && result.structuredResult() == null) return null;
        StructuredResultDTO dto = new StructuredResultDTO();
        dto.setEntityIds(result.entityIds() == null ? List.of() : result.entityIds());
        dto.setEntityCount(result.entityIds() == null ? 0 : result.entityIds().size());
        dto.setTruncated(false);
        QueryIntentV3 intent = result.intent();
        dto.setEntityType(intent != null ? intent.getEntityType() : null);
        dto.setScopeType(intent != null && intent.getSelection() != null ? intent.getSelection().getType().name() : null);
        StructuredQueryResult structured = result.structuredResult();
        if (structured != null) {
            dto.setMetricCode(structured.getMetricCode());
            dto.setOperation(structured.getOperation() == null ? null : structured.getOperation().name());
            if (structured.getRows() != null && !structured.getRows().isEmpty()) {
                dto.setEntityKeys(structured.getRows().stream()
                        .map(r -> StrUtil.blankToDefault(r.getEntityKey(), String.valueOf(r.getEntityId()))).toList());
            }
        }
        if (intent != null && intent.getActions() != null && !intent.getActions().isEmpty()) {
            QueryIntentV3.Action first = intent.getActions().get(0);
            dto.setQueryType(first.getType().name());
            if (first.getFields() != null && first.getFields().size() == 1) dto.setFieldCode(first.getFields().get(0));
        }
        return dto;
    }

    private String route(QueryEngineV3.Result result) {
        if (result.state() == QueryEngineV3.State.CLARIFY) return "CLARIFY";
        if (result.state() == QueryEngineV3.State.UNANSWERABLE) return "ABSTAIN";
        if ("STRUCTURED".equals(result.executionMode())) return "STRUCTURED_QUERY";
        if ("CROSS_ENTITY_COMPARE".equals(result.executionMode())) return "HYBRID_RAG";
        return "HYBRID_RAG";
    }

    private String intentName(QueryIntentV3 intent) {
        if (intent == null || intent.getSelection() == null) return "QUERY_V3";
        String actions = intent.getActions() == null ? "" : intent.getActions().stream()
                .map(a -> a.getType() == null ? "UNKNOWN" : a.getType().name()).collect(Collectors.joining("+"));
        return "V3_" + intent.getSelection().getType().name() + (actions.isEmpty() ? "" : "_" + actions);
    }

    private double confidence(QueryEngineV3.Result result) {
        if (result.selectionGuarantee() != null && (result.selectionGuarantee().contains("COMPLETE")
                || result.selectionGuarantee().startsWith("CONTEXT"))) return 1D;
        if (result.evidences() == null || result.evidences().isEmpty()) return 1D;
        return result.evidences().stream().map(Evidence::getScore).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.8D);
    }

    private String reasonMessage(QueryEngineV3.Result result) {
        if (result.intent() != null
                && result.intent().getPlannerStatus() == QueryIntentV3.PlannerStatus.FAILED) {
            return "查询规划服务未能生成通过 Schema 契约的执行计划（" + result.reasonCode() + "）。";
        }
        if ("EXACT_ENTITY_NOT_FOUND".equals(result.reasonCode())) {
            return "当前知识库中没有找到与该业务标识符完全匹配的已发布对象。";
        }
        return "当前查询无法可靠完成" + (StrUtil.isBlank(result.reasonCode()) ? "。" : "（" + result.reasonCode() + "）。");
    }

    private List<Long> contextEntityIds(String contextResolutionJson) {
        if (StrUtil.isBlank(contextResolutionJson)) return List.of();
        try {
            StructuredContextHint hint = JSONUtil.toBean(contextResolutionJson, StructuredContextHint.class);
            return hint == null || hint.getExplicitEntityIds() == null ? List.of() : hint.getExplicitEntityIds();
        } catch (Exception e) {
            log.warn("[query-v3][contextResolution parse failed: {}]", e.getMessage());
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
        try { return StrUtil.isBlank(value) ? null : Long.parseLong(value); } catch (Exception e) { return null; }
    }

    private String newTraceId() {
        return "ev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
