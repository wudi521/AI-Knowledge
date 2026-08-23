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
 * 证据评估编排服务: 检索组装 → 去重 → 冲突 → 充分性 → (可作答?)生成+Claim验证 → 落库 → 响应
 * <p>
 * 降级原则(永不抛出):
 * <ul>
 *     <li>检索 RPC 失败 → 空证据 → answerable=false + "未检索到相关证据"(仍落库, evidence_count=0);</li>
 *     <li>管线内部异常 → answerable=false + "评估过程异常: ..."(保留已产出部分, 仍落库);</li>
 *     <li>落库失败 → 吞异常 log warn, 不阻断响应;</li>
 *     <li>仅认证/参数校验错误由框架抛出。</li>
 * </ul>
 */
@Slf4j
@Service
public class EvidenceService {

    /** 无证据时的拒绝原因 */
    private static final String NO_EVIDENCE_REASON = "未检索到相关证据";

    @Resource
    private EvidenceAssembler assembler;
    @Resource
    private EvidenceDeduplicator deduplicator;
    @Resource
    private ConflictDetector conflictDetector;
    @Resource
    private SufficiencyJudge sufficiencyJudge;
    @Resource
    private AnswerPipeline answerPipeline;
    @Resource
    private EvidenceRecorder recorder;
    @Resource
    private SlotDetector slotDetector;
    @Resource
    private RuleShortCircuit ruleShortCircuit;
    @Resource
    private cn.iocoder.yudao.module.evidence.service.rule.PatentCountShortcut patentCountShortcut;
    @Resource
    private cn.iocoder.yudao.module.knowledge.api.KnowledgeApi knowledgeApi;
    @Resource
    private EvidenceProperties properties;

    /**
     * 证据评估(Controller 直连场景; 租户/用户取自登录态, 缺失时透传 null 由检索 RPC 自行降级)
     *
     * @param query 评估问题
     * @param kbIds 限定知识库编号列表(空 = 全部可见知识库)
     * @param topK  证据条数(空则默认 8)
     * @return 评估结果(永不抛异常)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK) {
        return evaluate(query, kbIds, topK, (Boolean) null);
    }

    /**
     * 证据评估(Controller 直连场景; 支持跳过槽位检测)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Boolean skipSlotDetection) {
        // 登录态: 经网关有登录用户; 缺失(如本地直连无 token)时透传 null
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;
        return evaluate(query, kbIds, topK, tenantId, userId, null, skipSlotDetection);
    }

    /**
     * 证据评估(Feign RPC 场景: 无登录态, 租户/用户由调用方显式传递)
     *
     * @param query    评估问题
     * @param kbIds    限定知识库编号列表(空 = 全部可见知识库)
     * @param topK     证据条数(空则默认 8)
     * @param tenantId 租户编号(可为 null, 由检索 RPC 自行降级)
     * @param userId   用户编号(可为 null, 权限过滤失效时降级)
     * @return 评估结果(永不抛异常)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId) {
        return evaluate(query, kbIds, topK, tenantId, userId, null);
    }

    /**
     * 证据评估(Feign RPC 场景: 无登录态, 租户/用户由调用方显式传递; 支持多轮上下文)
     *
     * @param query    评估问题
     * @param kbIds    限定知识库编号列表(空 = 全部可见知识库)
     * @param topK     证据条数(空则默认 8)
     * @param tenantId 租户编号(可为 null, 由检索 RPC 自行降级)
     * @param userId   用户编号(可为 null, 权限过滤失效时降级)
     * @param history  上下文轮次(可选, 空/ null = 单轮)
     * @return 评估结果(永不抛异常)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, null);
    }

    /**
     * 证据评估(Feign RPC 场景; 支持多轮上下文 + 跳过槽位检测)
     *
     * @param skipSlotDetection 是否跳过槽位检测(评测/批处理用: 测检索+回答质量, 不走对话层反问门)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection) {
        return evaluate(query, kbIds, topK, tenantId, userId, history, skipSlotDetection, null);
    }

    /**
     * 证据评估(Feign RPC 场景; 支持多轮上下文 + 跳过槽位检测 + 统一主 traceId)
     *
     * @param skipSlotDetection 是否跳过槽位检测(评测/批处理用: 测检索+回答质量, 不走对话层反问门)
     * @param traceId           统一主 traceId(q- 前缀, 对话层下发; null 时本模块生成 ev- 兜底)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String incomingTraceId) {
        long start = System.currentTimeMillis();
        String traceId = StrUtil.isNotBlank(incomingTraceId) ? incomingTraceId : newTraceId();

        // 管线各环节产物(异常兜底时保留已产出部分)
        List<Evidence> deduped = Collections.emptyList();
        List<Conflict> conflicts = Collections.emptyList();
        Judgement judgement = null;
        GenerationResult generation = null;
        // 检索诊断透传(意图/实体/改写/通道统计; 供前端检索测试页单接口展示)
        cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO.RetrievalAnalysisDTO evalAnalysis = null;
        cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO.RetrievalChannelStatDTO evalChannels = null;

        // 0. 硬规则优先(命中规则直接给结论, 不走检索/生成; 未命中/RPC 失败 → null 继续原管线)
        //    放在槽位检测之前: 硬规则是确定性事实(如 跨省→3天), 不应被缺槽位反问阻塞
        RuleShortCircuit.RuleConclusion ruleConclusion = ruleShortCircuit.evaluate(query, Map.of("query", query));
        if (ruleConclusion != null) {
            judgement = buildJudgement(true, 1.0, null, 0, 0);
            EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, List.of(), List.of(), null, history);
            resp.setAnswer(ruleConclusion.text());
            resp.setRoute("RULE"); // RF2-05: 硬规则命中路由, 保证非 null
            resp.setElapsedMs((int) (System.currentTimeMillis() - start));
            recorder.record(resp, List.of(), List.of());
            return resp;
        }

        // P0-10: 专利计数确定性短路(计数问题不走 top-K RAG, 避免漏数; 0 LLM / 0 向量)
        String patentCountAnswer = patentCountShortcut.evaluate(query, kbIds);
        if (patentCountAnswer != null) {
            judgement = buildJudgement(true, 1.0, null, 0, 0);
            EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, List.of(), List.of(), null, history);
            resp.setAnswer(patentCountAnswer);
            resp.setRoute("RULE");
            resp.setElapsedMs((int) (System.currentTimeMillis() - start));
            recorder.record(resp, List.of(), List.of());
            return resp;
        }

        // 0. 槽位检测(检索之前; 缺必填槽位 → 反问短路, 不检索; 检测失败/无定义 → 走原流程)
        //    kbIds 为空(对话链路"全部可见"语义)时, 先解析用户可见知识库, 与检索同口径;
        //    解析失败/空集 → 跳过检测(降级, 不阻断), 避免对话场景槽位反问门失效
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
            // 多轮槽位闭环: 传入 history, 检测器合并历史已提供的槽位值, 避免跨轮重复反问
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
                        .map(m -> toMissingSlotValue(m)).collect(Collectors.toList()));
                resp.setClarifyQuestion("请补充以下信息:" + names);
                resp.setElapsedMs((int) (System.currentTimeMillis() - start));
                recorder.record(resp, List.of(), List.of());
                return resp;
            }
        }
        try {
            // 1. 组装(检索 RPC → 归一化证据; RPC 失败/无结果返回空集, 不抛出)
            AssembledEvidence assembled = assembler.assemble(query, kbIds, topK, tenantId, userId, history, traceId);
            evalAnalysis = assembled.getAnalysis();
            evalChannels = assembled.getChannels();
            List<Evidence> evidences = assembled.getEvidences() != null
                    ? assembled.getEvidences() : Collections.emptyList();
            if (evidences.isEmpty()) {
                // 2. 无证据短路: 不可作答 + 原因, 仍落库(eval 行 evidence_count=0)
                //    检索阻断原因优先(OUT_OF_SCOPE 超范围短路即返回空结果 + 阻断原因, 此处透传,
                //    否则会被 "未检索到相关证据" 覆盖而丢失; 品牌门禁有结果不受影响); 无阻断时回退默认原因
                String blockReason = Boolean.TRUE.equals(assembled.getAnswerBlocked()) ? assembled.getAnswerReason() : null;
                judgement = buildJudgement(false, 0.0,
                        StrUtil.isNotBlank(blockReason) ? blockReason : NO_EVIDENCE_REASON, 0, 0);
            } else {
                // 3. 去重 → 冲突 → 充分性(检索品牌一致性门禁阻断原因透传)
                deduped = deduplicator.dedupe(evidences).getDeduped();
                conflicts = conflictDetector.detect(deduped);
                judgement = sufficiencyJudge.judge(deduped, conflicts, assembled.getQuestionProducts(),
                        Boolean.TRUE.equals(assembled.getAnswerBlocked()) ? assembled.getAnswerReason() : null);
                // 4. 可作答 → 生成 + Claim 逐句验证(claimFail=true 时 answer 恒为 null, 管线保证)
                //    历史仅透传生成/验证提示词(指代理解), 判定与检索不消费
                if (Boolean.TRUE.equals(judgement.getAnswerable())) {
                    generation = answerPipeline.generateWithClaims(query, deduped, history);
                }
            }
        } catch (Exception e) {
            // 永不抛出: 任何内部异常 → 不可作答 + 原因
            log.warn("[evaluate][query({}) 评估管线异常, 降级为不可作答: {}]", query, e.getMessage(), e);
            judgement = buildJudgement(false, 0.0,
                    "评估过程异常: " + StrUtil.maxLength(e.getMessage(), 200),
                    deduped.size(), conflicts.size());
        }

        // 5. 组装响应(elapsed 不含落库耗时)
        EvidenceEvaluateRespVO resp = buildResp(traceId, query, judgement, deduped, conflicts, generation, history);
        // 检索诊断透传(意图/实体/改写/通道统计; 供前端检索测试页单接口展示)
        resp.setAnalysis(evalAnalysis);
        resp.setChannels(evalChannels);
        // RF2-05/06: 检索路由透传(Query Planner 权威产出; 规则命中已在短路分支显式 RULE)
        resp.setRoute(evalAnalysis != null ? evalAnalysis.getRoute() : null);
        // 槽位检测结果回显(槽位完整时也回显, 供审计/后续合并用)
        if (slotResult != null && slotKbIds != null && !slotKbIds.isEmpty()) {
            resp.setSlotKbId(slotKbIds.get(0));
            resp.setExtractedSlots(toSlotValueList(slotResult.getExtracted()));
            if (!slotResult.getMissing().isEmpty()) {
                String names = slotResult.getMissing().stream()
                        .map(SlotDetectionResult.MissingSlot::getName)
                        .collect(Collectors.joining("、"));
                resp.setMissingSlots(slotResult.getMissing().stream()
                        .map(m -> toMissingSlotValue(m)).collect(Collectors.toList()));
                resp.setClarifyQuestion("请补充以下信息:" + names);
            }
        }
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));
        // P0-09: 汇聚全链路阶段时序(检索阶段 + 证据阶段), 供对话层落库 Query Trace
        resp.setStages(buildStages(evalAnalysis, generation, resp.getElapsedMs()));

        // 6. 落库(内部吞异常, 失败不阻断响应)
        recorder.record(resp, deduped, conflicts);
        return resp;
    }

    // ========== 响应组装 ==========

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
        // 证据列表 = 去重后列表(保持顺序, 与 conflicts/claims 索引一一对应)
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

    /**
     * P0-09: 汇聚全链路阶段时序 = 检索阶段(Query Planner 产出) + 证据阶段(组装/生成/验证/修复)。
     * 仅记录阶段/状态/耗时摘要, 不含敏感内容。
     */
    private List<QueryStageTimingDTO> buildStages(RetrievalSearchRespDTO.RetrievalAnalysisDTO evalAnalysis,
                                                  GenerationResult generation, int elapsedMs) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        if (evalAnalysis != null && evalAnalysis.getStages() != null) {
            stages.addAll(evalAnalysis.getStages());
            seq = evalAnalysis.getStages().size();
        }
        // EVIDENCE 阶段 = 检索结果组装/去重/冲突/充分性(总耗时减去生成/验证/修复)
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
            QueryStageTimingDTO verify = buildStage("VERIFY", ++seq, generation.getVerifyMs(),
                    success ? "SUCCEEDED" : "FAILED", null,
                    success ? null : "断言未全部通过证据验证 (outcome=" + generation.getOutcome() + ")");
            stages.add(verify);
            if (generation.getVerifyCount() > 1) {
                // repair 链路显式: GENERATE → VERIFY(FAILED) → REPAIR → VERIFY
                stages.add(buildStage("REPAIR", ++seq, generation.getRepairMs(),
                        "SUCCEEDED", null, null));
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
        dto.setSkipped(false);
        dto.setErrorCode(errorCode);
        dto.setErrorMessage(errorMessage);
        return dto;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO toEvidenceItem(Evidence evidence) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setEvidenceId(evidence.getChunkId());
        vo.setChunkId(evidence.getChunkId());
        vo.setContent(evidence.getContent());
        vo.setChunkMetadata(evidence.getChunkMetadata()); // 专利来源卡片
        vo.setDocumentName(evidence.getDocumentName());
        vo.setVersionNo(evidence.getVersionNo());
        vo.setVersionId(evidence.getVersionId());
        vo.setDocumentId(evidence.getDocumentId() != null ? Long.parseLong(evidence.getDocumentId()) : null);
        vo.setScore(evidence.getScore());
        vo.setChannels(evidence.getChannels() != null ? new ArrayList<>(evidence.getChannels()) : new ArrayList<>());
        // P0-08: 解析片段元数据为结构化字段(仅供前端证据卡片/抽屉展示, 不暴露内部 JSON)
        fillPatentMetadata(vo, evidence.getChunkMetadata());
        return vo;
    }

    /** 解析专利片段元数据 → 结构化字段(applicationNo/publicationNo/sectionType/sectionTitle/claimNo/pageStart/pageEnd) */
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

    /** 追踪号: ev- + 12 位 UUID 短串(对齐 ai_evidence_eval.trace_id varchar(64)) */
    private String newTraceId() {
        return "ev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 抽取 Map → SlotValueVO 列表(保序) */
    private List<EvidenceEvaluateRespVO.SlotValueVO> toSlotValueList(Map<String, String> extracted) {
        List<EvidenceEvaluateRespVO.SlotValueVO> list = new ArrayList<>();
        if (extracted != null) {
            extracted.forEach((code, value) -> {
                EvidenceEvaluateRespVO.SlotValueVO vo = new EvidenceEvaluateRespVO.SlotValueVO();
                vo.setCode(code);
                vo.setName(code); // 抽取阶段无 name, 展示兜底用 code
                vo.setValue(StrUtil.isBlank(value) ? null : value);
                list.add(vo);
            });
        }
        return list;
    }

    /** 缺失槽位 → SlotValueVO(value=null) */
    private EvidenceEvaluateRespVO.SlotValueVO toMissingSlotValue(SlotDetectionResult.MissingSlot m) {
        EvidenceEvaluateRespVO.SlotValueVO vo = new EvidenceEvaluateRespVO.SlotValueVO();
        vo.setCode(m.getCode());
        vo.setName(m.getName());
        vo.setValue(null);
        return vo;
    }

}
