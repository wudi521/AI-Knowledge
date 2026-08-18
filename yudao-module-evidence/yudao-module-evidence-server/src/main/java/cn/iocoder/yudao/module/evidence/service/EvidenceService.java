package cn.iocoder.yudao.module.evidence.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.domain.Judgement;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceDeduplicator;
import cn.iocoder.yudao.module.evidence.service.conflict.ConflictDetector;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.evidence.service.sufficiency.SufficiencyJudge;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /**
     * 证据评估(Controller 直连场景; 租户/用户取自登录态, 缺失时透传 null 由检索 RPC 自行降级)
     *
     * @param query 评估问题
     * @param kbIds 限定知识库编号列表(空 = 全部可见知识库)
     * @param topK  证据条数(空则默认 8)
     * @return 评估结果(永不抛异常)
     */
    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK) {
        // 登录态: 经网关有登录用户; 缺失(如本地直连无 token)时透传 null
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;
        return evaluate(query, kbIds, topK, tenantId, userId);
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
        long start = System.currentTimeMillis();
        String traceId = newTraceId();

        // 管线各环节产物(异常兜底时保留已产出部分)
        List<Evidence> deduped = Collections.emptyList();
        List<Conflict> conflicts = Collections.emptyList();
        Judgement judgement = null;
        GenerationResult generation = null;
        try {
            // 1. 组装(检索 RPC → 归一化证据; RPC 失败/无结果返回空集, 不抛出)
            AssembledEvidence assembled = assembler.assemble(query, kbIds, topK, tenantId, userId, history);
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
        resp.setElapsedMs((int) (System.currentTimeMillis() - start));

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
        } else {
            resp.setClaimFail(false);
        }
        return resp;
    }

    private EvidenceEvaluateRespVO.EvidenceItemVO toEvidenceItem(Evidence evidence) {
        EvidenceEvaluateRespVO.EvidenceItemVO vo = new EvidenceEvaluateRespVO.EvidenceItemVO();
        vo.setChunkId(evidence.getChunkId());
        vo.setContent(evidence.getContent());
        vo.setDocumentName(evidence.getDocumentName());
        vo.setVersionNo(evidence.getVersionNo());
        vo.setScore(evidence.getScore());
        vo.setChannels(evidence.getChannels() != null ? new ArrayList<>(evidence.getChannels()) : new ArrayList<>());
        return vo;
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

}
