package cn.iocoder.yudao.module.evidence.service.record;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.dal.dataobject.evidence.EvidenceEvalDO;
import cn.iocoder.yudao.module.evidence.dal.dataobject.evidence.EvidenceRecordDO;
import cn.iocoder.yudao.module.evidence.dal.mysql.evidence.EvidenceEvalMapper;
import cn.iocoder.yudao.module.evidence.dal.mysql.evidence.EvidenceRecordMapper;
import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 证据落库器: 写 ai_evidence_eval(会话级 1 行) + ai_evidence(证据级每证据 1 行)
 * <p>
 * verdict 语义(记 SUPPORTED/冲突/无关):
 * <ul>
 *     <li>被任一冲突引用(indexA/indexB 命中) → WARN(证据存在矛盾, 需人工关注);</li>
 *     <li>被任一 SUPPORTED 断言引用(claims.evidenceIndex 命中且 verdict=SUPPORTED) → SUPPORTED(实际支撑了回答);</li>
 *     <li>其余(相关但未被引用 / claimFail 无有效断言 / 未作答) → WARN。</li>
 * </ul>
 * 注: 本任务不写 UNSUPPORTED —— 无据断言由管线以 claimFail=true + answer=null 整体拦截,
 * 证据本身不单独标记 UNSUPPORTED(保留给后续任务按需扩展)。
 * <p>
 * 健壮性: 落库失败不阻断响应(整体 try/catch, log warn)。
 */
@Slf4j
@Component
public class EvidenceRecorder {

    private static final String VERDICT_SUPPORTED = "SUPPORTED";
    private static final String VERDICT_WARN = "WARN";

    @Resource
    private EvidenceEvalMapper evalMapper;
    @Resource
    private EvidenceRecordMapper recordMapper;

    /**
     * 落库(会话级 + 证据级)
     *
     * @param resp      评估响应(含 traceId/query/answerable/claims/conflicts/elapsedMs 等)
     * @param evidences 去重后的证据列表(位置索引与 conflicts/claims 一一对应; 可为空)
     * @param conflicts 冲突列表(domain 对象, 用于 verdict 判定)
     */
    public void record(EvidenceEvaluateRespVO resp, List<Evidence> evidences, List<Conflict> conflicts) {
        try {
            // 1. 会话级评估记录(1 行)
            EvidenceEvalDO eval = new EvidenceEvalDO();
            eval.setTraceId(resp.getTraceId());
            eval.setQuery(StrUtil.maxLength(resp.getQuery(), 500));
            eval.setAnswerable(Boolean.TRUE.equals(resp.getAnswerable()) ? 1 : 0);
            eval.setConfidence(toConfidence(resp.getConfidence()));
            eval.setRefusalReason(StrUtil.maxLength(resp.getRefusalReason(), 500));
            eval.setEvidenceCount(resp.getEvidence() != null ? resp.getEvidence().size() : 0);
            eval.setConflictCount(resp.getConflicts() != null ? resp.getConflicts().size() : 0);
            eval.setAnswer(resp.getAnswer());
            // claimPass: 有回答 且 非验证失败 → 全部通过
            eval.setClaimPass(resp.getAnswer() != null && !Boolean.TRUE.equals(resp.getClaimFail()) ? 1 : 0);
            eval.setClaims(resp.getClaims() != null ? JSONUtil.toJsonStr(resp.getClaims()) : null);
            eval.setConflicts(resp.getConflicts() != null ? JSONUtil.toJsonStr(resp.getConflicts()) : null);
            // 上下文快照: 非空才落库, 单轮/缺失为 null
            eval.setHistory(resp.getHistory() != null && !resp.getHistory().isEmpty()
                    ? JSONUtil.toJsonStr(resp.getHistory()) : null);
            eval.setElapsedMs(resp.getElapsedMs());
            eval.setCreator(creator());
            evalMapper.insert(eval);

            // 2. 证据级记录(每证据 1 行; 无证据时跳过)
            if (evidences != null && !evidences.isEmpty()) {
                for (int i = 0; i < evidences.size(); i++) {
                    Evidence evidence = evidences.get(i);
                    EvidenceRecordDO record = new EvidenceRecordDO();
                    record.setChunkId(evidence.getChunkId());
                    record.setConfidence(toConfidence(evidence.getScore()));
                    record.setVerdict(verdictOf(i, conflicts, resp));
                    record.setTraceId(resp.getTraceId());
                    record.setCreator(creator());
                    recordMapper.insert(record);
                }
            }
        } catch (Exception e) {
            // 落库失败不阻断响应
            log.warn("[record][traceId({}) 证据落库失败, 跳过: {}]",
                    resp != null ? resp.getTraceId() : null, e.getMessage(), e);
        }
    }

    /**
     * verdict 判定(见类注释)
     */
    private String verdictOf(int index, List<Conflict> conflicts, EvidenceEvaluateRespVO resp) {
        // 1. 冲突引用 → WARN
        if (conflicts != null) {
            for (Conflict conflict : conflicts) {
                if (conflict.getEvidenceIndexA() != null && conflict.getEvidenceIndexA() == index) {
                    return VERDICT_WARN;
                }
                if (conflict.getEvidenceIndexB() != null && conflict.getEvidenceIndexB() == index) {
                    return VERDICT_WARN;
                }
            }
        }
        // 2. 有回答且被 SUPPORTED 断言引用 → SUPPORTED
        if (resp.getAnswer() != null && resp.getClaims() != null) {
            for (EvidenceEvaluateRespVO.ClaimVO claim : resp.getClaims()) {
                if (claim != null && claim.getEvidenceIndex() != null && claim.getEvidenceIndex() == index
                        && VERDICT_SUPPORTED.equals(claim.getVerdict())) {
                    return VERDICT_SUPPORTED;
                }
            }
        }
        // 3. 其余(未引用/claimFail/未作答) → WARN
        return VERDICT_WARN;
    }

    /** 置信度: 钳制 0~1 + 保留 4 位小数(对齐 decimal(5,4)) */
    private BigDecimal toConfidence(Double score) {
        if (score == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        double value = Math.max(0.0, Math.min(1.0, score));
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    /** 创建人: 登录用户昵称(LoginUser 无 username 字段, 用昵称兜底), 缺失为空串(框架自动填充 userId) */
    private String creator() {
        String nickname = SecurityFrameworkUtils.getLoginUserNickname();
        return nickname != null ? nickname : "";
    }

}
