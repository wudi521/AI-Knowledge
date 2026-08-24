package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.domain.Judgement;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceDeduplicator;
import cn.iocoder.yudao.module.evidence.service.conflict.ConflictDetector;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.evidence.service.rule.RuleShortCircuit;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompositeQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompositeQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredContextHint;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryService;
import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import cn.iocoder.yudao.module.evidence.service.slot.SlotDetectionResult;
import cn.iocoder.yudao.module.evidence.service.slot.SlotDetector;
import cn.iocoder.yudao.module.evidence.service.sufficiency.SufficiencyJudge;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 证据评估编排服务: 检索组装 → 去重 → 冲突 → 充分性 → (可作答?)生成+Claim验证 → 落库 → 响应。
 */
@Slf4j
@Service
public class EvidenceService {

    private static final String NO_EVIDENCE_REASON = "未检索到相关证据";

    @Resource private EvidenceAssembler assembler;
    @Resource private EvidenceDeduplicator deduplicator;
    @Resource private ConflictDetector conflictDetector;
    @Resource private SufficiencyJudge sufficiencyJudge;
    @Resource private AnswerPipeline answerPipeline;
    @Resource private EvidenceRecorder recorder;
    @Resource private SlotDetector slotDetector;
    @Resource private RuleShortCircuit ruleShortCircuit;
    @Resource private cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard completenessGuard;
    @Resource private cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryService structuredQueryService;
    @Resource private cn.iocoder.yudao.module.evidence.service.structured.core.CompositeQueryExecutor compositeQueryExecutor;
    @Resource private cn.iocoder.yudao.module.knowledge.api.KnowledgeApi knowledgeApi;
    @Resource private EvidenceProperties properties;

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK) {
        return evaluate(query, kbIds, topK, (Boolean) null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Boolean skipSlotDetection) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;
        return evaluate(query, kbIds, topK, tenantId, userId, null, skipSlotDetection);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId) {
        return evaluate(query, kbIds, topK, tenantId, userId, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, skipSlotDetection, null, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, skipSlotDetection, incomingTraceId, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId,
                                           String domainCode) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, skipSlotDetection, incomingTraceId,
                domainCode, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId,
                                           String domainCode, String contextResolutionJson) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, skipSlotDetection, incomingTraceId,
                domainCode, contextResolutionJson, null);
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId,
                                           String domainCode, String contextResolutionJson,
                                           QueryPlanBudgetDTO planBudget) {
        long start = System.currentTimeMillis();
        String traceId = StrUtil.isNotBlank(incomingTraceId) ? incomingTraceId : newTraceId();

        List<Evidence> deduped = Collections.emptyList();
        List<Conflict> conflicts = Collections.emptyList();
        Judgement judgement = null;
        GenerationResult generation = null;
        RetrievalSearchRespDTO.RetrievalAnalysisDTO evalAnalysis = null;
        RetrievalSearchRespDTO.RetrievalChannelStatDTO evalChannels = null;

        RuleShortCircuit.RuleConclusion ruleConclusion = ruleShortCircuit.evaluate(query, Map.of("query", query));
        if (ruleConclusion != null) {
            judgement = buildJudgement(true, 1.0, null, 0, 0);
            EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, List.of(), List.of(), null, history);
            resp.setAnswer(ruleConclusion.text());
            resp.setRoute("RULE");
            resp.setElapsedMs((int) (System.currentTimeMillis() - start));
            recorder.record(resp, List.of(), List.of());
            return resp;
        }

        boolean structuredCandidate = completenessGuard.isStructuredCandidate(query);
        boolean completenessSemantics = completenessGuard.requiresCompleteDataset(query);
        if (structuredCandidate || completenessSemantics) {
            Long singleKbId = kbIds != null && kbIds.size() == 1 ? kbIds.get(0) : null;
            CompositeQueryExecutor.Result composite = null;
            if (structuredCandidate && domainCode != null) {
                StructuredContextHint hint = parseContextHint(contextResolutionJson);
                CompositeQueryExecutor.Request req = new CompositeQueryExecutor.Request(
                        query, singleKbId, domainCode, history,
                        hint != null ? hint.getExplicitEntityIds() : null,
                        hint != null ? hint.getFieldCode() : null,
                        tenantId, userId, traceId, CompositeQueryPlan.Budget.of(planBudget));
                composite = compositeQueryExecutor.execute(req);
            }
            if (composite != null && composite.state() == StructuredQueryService.State.ANSWER) {
                EvidenceEvaluateRespVO resp = buildCompositeAnswerResp(traceId, query, history, composite, start);
                recorder.record(resp, safeEvidences(composite), List.of());
                return resp;
            }
            if (composite != null && composite.state() == StructuredQueryService.State.CLARIFY) {
                EvidenceEvaluateRespVO resp = buildCompositeClarifyResp(traceId, query, history, composite, start);
                recorder.record(resp, safeEvidences(composite), List.of());
                return resp;
            }
            // 非结构化执行模式失败不能被包装成“完整数据集统计失败”。
            if (composite != null && composite.state() == StructuredQueryService.State.UNANSWERABLE
                    && !completenessSemantics && isSemanticExecutionMode(composite.executionMode())) {
                String reason = composite.timedOut() ? "本次查询执行超时，请缩小查询范围或稍后重试。"
                        : "当前查询无法可靠完成，请调整查询范围或稍后重试。";
                judgement = buildJudgement(false, 0.0, reason, safeEvidences(composite).size(), 0);
                EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, safeEvidences(composite), List.of(),
                        composite.generation(), history);
                resp.setRoute("ABSTAIN");
                resp.setIntent(composite.executionMode() + "_REJECTED");
                resp.setExecutionMode(composite.executionMode());
                resp.setReasonCode(composite.reasonCode());
                resp.setElapsedMs((int) (System.currentTimeMillis() - start));
                resp.setStages(buildExecutionStages(resp.getElapsedMs(), "REJECTED",
                        composite.executionMode(), composite.generation()));
                recorder.record(resp, safeEvidences(composite), List.of());
                return resp;
            }
            if (composite == null || composite.state() == StructuredQueryService.State.UNANSWERABLE
                    || completenessSemantics) {
                boolean timedOut = composite != null && composite.timedOut();
                String reason = timedOut ? "本次查询执行超时，请缩小查询范围或稍后重试。"
                        : "该问题需要基于全部数据统计，当前无法可靠回答。";
                judgement = buildJudgement(false, 0.0, reason, 0, 0);
                EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, List.of(), List.of(), null, history);
                resp.setRoute("STRUCTURED_QUERY");
                resp.setIntent(timedOut ? "STRUCTURED_QUERY_TIMEOUT" : "STRUCTURED_QUERY_REJECTED");
                resp.setExecutionMode(composite != null && composite.executionMode() != null
                        ? composite.executionMode() : ExecutionMode.CODE_STRUCTURED);
                resp.setReasonCode(composite != null ? composite.reasonCode() : "AMBIGUOUS_SCOPE");
                resp.setElapsedMs((int) (System.currentTimeMillis() - start));
                resp.setStages(buildStructuredStages(resp.getElapsedMs(), "REJECTED"));
                recorder.record(resp, List.of(), List.of());
                return resp;
            }
        }

        SlotDetectionResult slotResult = null;
        List<Long> slotKbIds = kbIds;
        if (!Boolean.TRUE.equals(skipSlotDetection)
                && Boolean.TRUE.equals(properties.getSlot().getEnabled())
                && (kbIds == null || kbIds.isEmpty())) {
            try {
                java.util.Set<Long> visible = knowledgeApi.getVisibleKbIds(userId).getCheckedData();
                slotKbIds = visible == null ? List.of() : new java.util.ArrayList<>(visible);
            } catch (Exception e) {
                log.warn("[evaluate][可见知识库解析失败, 跳过槽位检测: {}]", e.getMessage());
                slotKbIds = List.of();
            }
        }
        if (!Boolean.TRUE.equals(skipSlotDetection)
                && Boolean.TRUE.equals(properties.getSlot().getEnabled())
                && slotKbIds != null && !slotKbIds.isEmpty()) {
            slotResult = slotDetector.detect(query, slotKbIds, history);
            if (slotResult != null && slotResult.isApplicable() && !slotResult.getMissing().isEmpty()) {
                String names = slotResult.getMissing().stream()
                        .map(SlotDetectionResult.MissingSlot::getName)
                        .collect(Collectors.joining("、"));
                judgement = buildJudgement(false, 0.0, "需补充信息:" + names, 0, 0);
                EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, List.of(), List.of(), null, history);
                resp.setSlotKbId(slotKbIds.get(0));
                resp.setExtractedSlots(toSlotValueList(slotResult.getExtracted()));
                resp.setMissingSlots(slotResult.getMissing().stream()
                        .map(this::toMissingSlotValue).collect(Collectors.toList()));
                resp.setClarifyQuestion("请补充以下信息:" + names);
                resp.setElapsedMs((int) (System.currentTimeMillis() - start));
                recorder.record(resp, List.of(), List.of());
                return resp;
            }
        }
        try {
            AssembledEvidence assembled = assembler.assemble(query, kbIds, topK, tenantId, userId, history, traceId);
            evalAnalysis = assembled.getAnalysis();
            evalChannels = assembled.getChannels();
            List<Evidence> evidences = assembled.getEvidences() != null
                    ? assembled.getEvidences() : Collections.emptyList();
            if (evidences.isEmpty()) {
                String blockReason = Boolean.TRUE.equals(assembled.getAnswerBlocked()) ? assembled.getAnswerReason() : null;
                judgement = buildJudgement(false, 0.0,
                        StrUtil.isNotBlank(blockReason) ? blockReason : NO_EVIDENCE_REASON, 0, 0);
            } else {
                deduped = deduplicator.dedupe(evidences).getDeduped();
                conflicts = conflictDetector.detect(deduped);
                judgement = sufficiencyJudge.judge(deduped, conflicts, assembled.getQuestionProducts(),
                        Boolean.TRUE.equals(assembled.getAnswerBlocked()) ? assembled.getAnswerReason() : null);
                if (Boolean.TRUE.equals(judgement.getAnswerable())) {
                    generation = answerPipeline.generateWithClaims(query, deduped, history);
                }
            }
        } catch (Exception e) {
            log.warn("[evaluate][query({}) 评估管线异常, 降级为不可作答: {}]", query, e.getMessage(), e);
            judgement = buildJudgement(false, 0.0,
                    "评估过程异常: " + StrUtil.maxLength(e.getMessage(), 200),
                    deduped.size(), conflicts.size());
        }

        EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, deduped, conflicts, generation, history);
        resp.setAnalysis(evalAnalysis);
        resp.setChannels(evalChannels);
        resp.setRoute(evalAnalysis != null ? evalAnalysis.getRoute() : null);
        if (slotResult != null && slotKbIds != null && !slotKbIds.isEmpty()) {
            resp.setSlotKbId(slotKbIds.get(0));
            resp.setExtractedSlots(toSlotValueList(slotResult.getExtracted()));
            if (!slotResult.getMissing().isEmpty()) {
                String names = slotResult.getMissing().stream()
                        .map(SlotDetectionResult.MissingSlot::getName)
                        .collect(Collectors.joining("、"));
                resp.setMissingSlots(slotResult.getMissing().stream()
                        .map(this::toMissingSlotValue).collect(Collectors.toList()));
                resp.setClarifyQuestion("请补充以下信息:" + names);
            }
        }
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        resp.setStages(buildStages(evalAnalysis, generation, resp.getElapsedMs()));
        recorder.record(resp, deduped, conflicts);
        return resp;
    }

    private List<Evidence> safeEvidences(CompositeQueryExecutor.Result composite) {
        return composite != null && composite.evidences() != null ? composite.evidences() : List.of();
    }

    private EvidenceEvaluateRespVO buildResp(String traceId, String query, Judgement judgement,
                                             List<Evidence> evidences, List<Conflict> conflicts,
                                             GenerationResult generation, List<ChatTurnDTO> history) {
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setTraceId(traceId);
        resp.setQuery(query);
        resp.setAnswerable(judgement.getAnswerable());
        resp.setConfidence(judgement.getConfidence());
        resp.setConsultable(judgement.getConsultable());
        resp.setRefusalReason(Boolean.TRUE.equals(judgement.getAnswerable()) ? null : judgement.getReason());
        resp.setHistory(history);
        resp.setEvidence(evidences.stream().map(this::toEvidenceItem).collect(Collectors.toList()));
        resp.setConflicts(conflicts.stream().map(this::toConflictVO).collect(Collectors.toList()));
        if (generation != null) {
            resp.setAnswer(generation.getAnswer());
            resp.setClaims(generation.getClaims() != null
                    ? generation.getClaims().stream().map(this::toClaimVO).collect(Collectors.toList()) : null);
            resp.setClaimFail(generation.isClaimFail());
            resp.setVerificationDegraded(generation.isVerificationDegraded());
            resp.setTimedOut(generation.isTimedOut());
        } else {
            resp.setClaimFail(false);
        }
        return resp;
    }

    private List<QueryStageTimingDTO> buildStages(RetrievalSearchRespDTO.RetrievalAnalysisDTO evalAnalysis,
                                                  GenerationResult generation, int elapsedMs) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        if (evalAnalysis != null && evalAnalysis.getStages() != null) {
            stages.addAll(evalAnalysis.getStages());
            seq = evalAnalysis.getStages().size();
        }
        long genTotal = generation != null
                ? generation.getGenerateMs() + generation.getVerifyMs() + generation.getRepairMs() : 0;
        stages.add(buildStage("EVIDENCE", ++seq, Math.max(0, elapsedMs - genTotal),
                "SUCCEEDED", null, null));
        if (generation != null && generation.getGenerateCount() > 0) {
            boolean success = "success".equals(generation.getOutcome());
            QueryStageTimingDTO generate = buildStage("GENERATE", ++seq, generation.getGenerateMs(),
                    "SUCCEEDED", null, null);
            generate.setOutputSummary("generateCount=" + generation.getGenerateCount());
            stages.add(generate);
            stages.add(buildStage("VERIFY", ++seq, generation.getVerifyMs(),
                    success ? "SUCCEEDED" : "FAILED", null,
                    success ? null : "断言未全部通过证据验证 (outcome=" + generation.getOutcome() + ")"));
            if (generation.getVerifyCount() > 1) {
                stages.add(buildStage("REPAIR", ++seq, generation.getRepairMs(), "SUCCEEDED", null, null));
                stages.add(buildStage("VERIFY", ++seq, 0,
                        success ? "SUCCEEDED" : "FAILED", null,
                        success ? null : "二次验证仍未通过 (outcome=" + generation.getOutcome() + ")"));
            }
        }
        return stages;
    }

    private QueryStageTimingDTO buildStage(String stage, int seq, long elapsedMs, String status,
                                           String errorCode, String errorMessage) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage(stage);
        dto.setSeq(seq);
        dto.setStatus(status);
        dto.setElapsedMs(elapsedMs);
        dto.setSkipped("SKIPPED".equals(status));
        dto.setErrorCode(errorCode);
        dto.setErrorMessage(errorMessage);
        return dto;
    }

    private StructuredContextHint parseContextHint(String contextResolutionJson) {
        if (StrUtil.isBlank(contextResolutionJson)) return null;
        try {
            StructuredContextHint hint = cn.hutool.json.JSONUtil.toBean(contextResolutionJson, StructuredContextHint.class);
            if (hint == null || (cn.hutool.core.collection.CollUtil.isEmpty(hint.getExplicitEntityIds())
                    && StrUtil.isBlank(hint.getFieldCode()) && StrUtil.isBlank(hint.getMetricCode()))) return null;
            return hint;
        } catch (Exception e) {
            log.warn("[parseContextHint][contextResolutionJson 解析失败: {}]", e.getMessage());
            return null;
        }
    }

    private cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO buildStructuredResult(
            CompositeQueryExecutor.Result composite) {
        if (composite == null || composite.plan() == null) return null;
        cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult result = composite.structuredResult();
        cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO dto =
                new cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO();
        if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
            dto.setEntityIds(result.getRows().stream()
                    .map(cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult.Row::getEntityId).toList());
            dto.setEntityKeys(result.getRows().stream()
                    .map(r -> r.getEntityKey() != null ? r.getEntityKey() : String.valueOf(r.getEntityId())).toList());
        } else if (composite.entityIds() != null && !composite.entityIds().isEmpty()) {
            dto.setEntityIds(composite.entityIds());
        }
        dto.setEntityType(composite.plan().getEntityType());
        dto.setMetricCode(composite.plan().getMetricCode());
        dto.setFieldCode(composite.plan().getFieldCode());
        dto.setOperation(composite.plan().getOperation() != null ? composite.plan().getOperation().name() : null);
        dto.setQueryType(composite.plan().getQueryType() != null ? composite.plan().getQueryType().name() : null);
        dto.setScopeType(composite.plan().getScope() != null ? composite.plan().getScope().getType().name() : null);
        dto.setTruncated(result != null && result.isTruncated());
        dto.setEntityCount(result != null && result.getRows() != null
                ? result.getRows().size() : (composite.entityIds() != null ? composite.entityIds().size() : 0));
        return dto;
    }

    private EvidenceEvaluateRespVO buildCompositeAnswerResp(String traceId, String query, List<ChatTurnDTO> history,
                                                            CompositeQueryExecutor.Result composite, long start) {
        Judgement j = buildJudgement(true, 1.0, null, safeEvidences(composite).size(), 0);
        if (isSemanticExecutionMode(composite.executionMode())) {
            EvidenceEvaluateRespVO resp = buildResp(traceId, query, j, safeEvidences(composite), List.of(),
                    composite.generation(), history);
            resp.setAnswer(composite.answer());
            resp.setRoute(externalRoute(composite.executionMode()));
            resp.setIntent(composite.executionMode());
            resp.setExecutionMode(composite.executionMode());
            resp.setStructuredResult(buildSemanticsResultDTO(composite));
            resp.setElapsedMs((int) (System.currentTimeMillis() - start));
            resp.setStages(buildExecutionStages(resp.getElapsedMs(), "SUCCEEDED",
                    composite.executionMode(), composite.generation()));
            return resp;
        }

        EvidenceEvaluateRespVO resp = buildResp(traceId, query, j, List.of(), List.of(), null, history);
        resp.setAnswer(composite.answer());
        resp.setRoute("STRUCTURED_QUERY");
        resp.setIntent(composite.plan() != null ? subTypeIntent(composite.plan()) : "STRUCTURED_QUERY");
        resp.setExecutionMode(ExecutionMode.CODE_STRUCTURED);
        resp.setStructuredResult(buildStructuredResult(composite));
        EvidenceEvaluateRespVO.EvidenceItemVO ev = new EvidenceEvaluateRespVO.EvidenceItemVO();
        ev.setEvidenceType("STRUCTURED_RESULT");
        ev.setKbId(composite.plan() != null && composite.plan().getScope() != null
                ? composite.plan().getScope().getCurrentKbId() : null);
        ev.setDomainCode(composite.plan() != null ? composite.plan().getDomainCode() : null);
        ev.setMetric(composite.plan() != null ? composite.plan().getMetricCode() : null);
        if (composite.structuredResult() != null && composite.structuredResult().getValue() != null) {
            double v = composite.structuredResult().getValue();
            ev.setAggregateValue(v == Math.floor(v) ? (int) Math.round(v) : null);
        }
        ev.setFilters("operation=" + (composite.plan() != null ? composite.plan().getOperation() : null)
                + ",scope=" + (composite.plan() != null && composite.plan().getScope() != null
                ? composite.plan().getScope().getType() : "null")
                + ",rows=" + (composite.structuredResult() != null ? composite.structuredResult().getRowCount() : 0));
        ev.setContent(composite.answer());
        ev.setScore(1.0);
        resp.setEvidence(List.of(ev));
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        resp.setStages(buildStructuredStages(resp.getElapsedMs(), "SUCCEEDED"));
        return resp;
    }

    private EvidenceEvaluateRespVO buildCompositeClarifyResp(String traceId, String query, List<ChatTurnDTO> history,
                                                             CompositeQueryExecutor.Result composite, long start) {
        Judgement j = buildJudgement(false, 0.0, composite.clarificationQuestion(), safeEvidences(composite).size(), 0);
        EvidenceEvaluateRespVO resp = buildResp(traceId, query, j, safeEvidences(composite), List.of(), null, history);
        boolean semantic = isSemanticExecutionMode(composite.executionMode());
        resp.setRoute("CLARIFY");
        resp.setIntent(semantic && composite.executionMode() != null
                ? composite.executionMode() + "_CLARIFY" : "STRUCTURED_CLARIFY");
        resp.setClarifyQuestion(composite.clarificationQuestion());
        resp.setReasonCode(composite.reasonCode());
        resp.setExecutionMode(composite.executionMode() != null ? composite.executionMode() : ExecutionMode.CODE_STRUCTURED);
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        resp.setStages(semantic
                ? buildExecutionStages(resp.getElapsedMs(), "CLARIFY", composite.executionMode(), null)
                : buildStructuredStages(resp.getElapsedMs(), "CLARIFY"));
        return resp;
    }

    private boolean isSemanticExecutionMode(String mode) {
        return ExecutionMode.CODE_PER_ENTITY_SEMANTIC.equals(mode)
                || ExecutionMode.CODE_CROSS_ENTITY_SEMANTIC.equals(mode)
                || ExecutionMode.CODE_CROSS_ENTITY_COMPARE.equals(mode)
                || ExecutionMode.CODE_EXACT_TEXT_SEARCH.equals(mode)
                || ExecutionMode.CODE_SCOPED_RAG.equals(mode)
                || ExecutionMode.CODE_HYBRID_RAG.equals(mode);
    }

    /** 外部主路由保持兼容固定集合；内部能力通过 executionMode 表达。 */
    private String externalRoute(String executionMode) {
        if (ExecutionMode.CODE_SCOPED_RAG.equals(executionMode)) return "SCOPED_RAG";
        return "HYBRID_RAG";
    }

    private cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO buildSemanticsResultDTO(
            CompositeQueryExecutor.Result composite) {
        if (composite == null || composite.entityIds() == null || composite.entityIds().isEmpty()) return null;
        cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO dto =
                new cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO();
        dto.setEntityIds(composite.entityIds());
        dto.setEntityType(composite.plan() != null ? composite.plan().getEntityType() : null);
        dto.setQueryType("LIST");
        dto.setScopeType(composite.plan() != null && composite.plan().getScope() != null
                ? composite.plan().getScope().getType().name() : "ENTITY_SET");
        dto.setEntityCount(composite.entityIds().size());
        dto.setTruncated(false);
        return dto;
    }

    /** 不制造假耗时：生成/验证使用真实 GenerationResult timing，其余未细分阶段只记录总检索耗时。 */
    private List<QueryStageTimingDTO> buildExecutionStages(int elapsedMs, String outcome, String mode,
                                                           GenerationResult generation) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        stages.add(buildStage("ANALYZE", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("CONTEXT_RESOLVE", ++seq, 0, "SUCCEEDED", null, null));
        stages.add(buildStage("PLAN", ++seq, 0, "SUCCEEDED", null, null));
        long genMs = generation != null ? generation.getGenerateMs() : 0;
        long verifyMs = generation != null ? generation.getVerifyMs() : 0;
        long repairMs = generation != null ? generation.getRepairMs() : 0;
        long retrieveMs = Math.max(0, elapsedMs - genMs - verifyMs - repairMs);
        String retrieveStage = ExecutionMode.CODE_EXACT_TEXT_SEARCH.equals(mode) ? "EXACT_TEXT_RETRIEVE"
                : ExecutionMode.CODE_CROSS_ENTITY_COMPARE.equals(mode) ? "CROSS_ENTITY_RETRIEVE"
                : "PER_ENTITY_RETRIEVE";
        stages.add(buildStage(retrieveStage, ++seq, retrieveMs,
                "CLARIFY".equals(outcome) || "REJECTED".equals(outcome) ? outcome : "SUCCEEDED", null, null));
        if (ExecutionMode.CODE_CROSS_ENTITY_COMPARE.equals(mode)) {
            stages.add(buildStage("CROSS_ENTITY_COVERAGE", ++seq, 0,
                    "CLARIFY".equals(outcome) ? "FAILED" : "SUCCEEDED", null, null));
        }
        if (generation != null && generation.getGenerateCount() > 0) {
            stages.add(buildStage("GENERATE", ++seq, genMs, "SUCCEEDED", null, null));
            stages.add(buildStage("VERIFY", ++seq, verifyMs,
                    generation.isClaimFail() ? "FAILED" : "SUCCEEDED", null, null));
            if (repairMs > 0) stages.add(buildStage("REPAIR", ++seq, repairMs, "SUCCEEDED", null, null));
        } else {
            stages.add(buildStage("GENERATE", ++seq, 0, "SKIPPED", null, null));
            stages.add(buildStage("VERIFY", ++seq, 0, "SKIPPED", null, null));
        }
        stages.add(buildStage("ANSWER", ++seq, 0,
                "SUCCEEDED".equals(outcome) ? "SUCCEEDED" : outcome, null, null));
        return stages;
    }

    private String subTypeIntent(StructuredQueryPlan plan) {
        QueryType type = plan.getQueryType();
        return "STRUCTURED_" + (type == null ? "QUERY" : type.name());
    }

    private List<QueryStageTimingDTO> buildStructuredStages(int elapsedMs, String outcome) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        stages.add(buildStage("ANALYZE", ++seq, 1, "SUCCEEDED", null, null));
        stages.add(buildStage("CONTEXT_RESOLVE", ++seq, 1, "SUCCEEDED", null, null));
        stages.add(buildStage("PLAN", ++seq, 1, "SUCCEEDED", null, null));
        stages.add(buildStage("SCOPE_RESOLVE", ++seq, 1, "SUCCEEDED", null, null));
        stages.add(buildStage("METRIC_RESOLVE", ++seq, 1, "SUCCEEDED", null, null));
        stages.add(buildStage("STRUCTURED_EXECUTE", ++seq, Math.max(0, elapsedMs - 5), outcome, null, null));
        stages.add(buildStage("ANSWER", ++seq, 0, "SUCCEEDED", null, null));
        stages.add(buildStage("BM25", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("VECTOR", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("FUSION", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("RERANK", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("GENERATE", ++seq, 0, "SKIPPED", null, null));
        stages.add(buildStage("VERIFY", ++seq, 0, "SKIPPED", null, null));
        return stages;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO toEvidenceItem(Evidence evidence) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceId(evidence.getChunkId());
        vo.setChunkId(evidence.getChunkId());
        vo.setContent(evidence.getContent());
        vo.setChunkMetadata(evidence.getChunkMetadata());
        vo.setDocumentName(evidence.getDocumentName());
        vo.setVersionNo(evidence.getVersionNo());
        vo.setVersionId(evidence.getVersionId());
        try {
            vo.setDocumentId(StrUtil.isNotBlank(evidence.getDocumentId()) ? Long.parseLong(evidence.getDocumentId()) : null);
        } catch (NumberFormatException ignore) {
            vo.setDocumentId(null);
        }
        vo.setScore(evidence.getScore());
        vo.setChannels(evidence.getChannels() != null ? new ArrayList<>(evidence.getChannels()) : new ArrayList<>());
        fillPatentMetadata(vo, evidence.getChunkMetadata());
        return vo;
    }

    private void fillPatentMetadata(EvidenceEvaluateRespVO.EvidenceItemVO vo, String chunkMetadata) {
        if (StrUtil.isBlank(chunkMetadata)) return;
        try {
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(chunkMetadata);
            vo.setApplicationNo(obj.getStr("applicationNo"));
            vo.setPublicationNo(obj.getStr("publicationNo"));
            vo.setSectionType(obj.getStr("sectionType"));
            vo.setSectionTitle(obj.getStr("sectionTitle"));
            vo.setClaimNo(obj.getStr("claimNo"));
            vo.setPageStart(obj.getInt("pageStart"));
            vo.setPageEnd(obj.getInt("pageEnd"));
        } catch (Exception e) {
            log.debug("[fillPatentMetadata][元数据解析失败, 忽略: {}]", chunkMetadata);
        }
    }

    private EvidenceEvaluateRespVO.ConflictVO toConflictVO(Conflict conflict) {
        EvidenceEvaluateRespVO.ConflictVO vo = new EvidenceEvaluateRespVO.ConflictVO();
        vo.setEvidenceIndexA(conflict.getEvidenceIndexA());
        vo.setEvidenceIndexB(conflict.getEvidenceIndexB());
        vo.setReason(conflict.getReason());
        return vo;
    }

    private EvidenceEvaluateRespVO.ClaimVO toClaimVO(ClaimResult claim) {
        EvidenceEvaluateRespVO.ClaimVO vo = new EvidenceEvaluateRespVO.ClaimVO();
        vo.setText(claim.getText());
        vo.setVerdict(claim.getVerdict());
        vo.setEvidenceIndex(claim.getEvidenceIndex());
        return vo;
    }

    private Judgement buildJudgement(boolean answerable, double confidence, String reason,
                                     int evidenceCount, int conflictCount) {
        return Judgement.builder()
                .answerable(answerable)
                .confidence(confidence)
                .reason(reason)
                .evidenceCount(evidenceCount)
                .conflictCount(conflictCount)
                .consultable(false)
                .build();
    }

    private String newTraceId() {
        return "ev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private List<EvidenceEvaluateRespVO.SlotValueVO> toSlotValueList(Map<String, String> extracted) {
        List<EvidenceEvaluateRespVO.SlotValueVO> list = new ArrayList<>();
        if (extracted != null) {
            extracted.forEach((code, value) -> {
                EvidenceEvaluateRespVO.SlotValueVO vo = new EvidenceEvaluateRespVO.SlotValueVO();
                vo.setCode(code);
                vo.setName(code);
                vo.setValue(StrUtil.isBlank(value) ? null : value);
                list.add(vo);
            });
        }
        return list;
    }

    private EvidenceEvaluateRespVO.SlotValueVO toMissingSlotValue(SlotDetectionResult.MissingSlot m) {
        EvidenceEvaluateRespVO.SlotValueVO vo = new EvidenceEvaluateRespVO.SlotValueVO();
        vo.setCode(m.getCode());
        vo.setName(m.getName());
        vo.setValue(null);
        return vo;
    }
}
