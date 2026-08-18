package cn.iocoder.yudao.module.evidence.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceConflictDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.EvidenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 证据平台 对外 RPC 实现(Feign 调用, 无登录态, 租户/用户显式传递)
 */
@Slf4j
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class EvidenceApiImpl implements EvidenceApi {

    @Resource
    private EvidenceService evidenceService;

    @Override
    public CommonResult<EvidenceEvaluateRespDTO> evaluate(EvidenceEvaluateReqDTO req) {
        EvidenceEvaluateRespVO vo = evidenceService.evaluate(req.getQuery(), req.getKbIds(), req.getTopK(),
                req.getTenantId(), req.getUserId());
        // 映射 EvidenceEvaluateRespVO -> EvidenceEvaluateRespDTO
        EvidenceEvaluateRespDTO dto = new EvidenceEvaluateRespDTO();
        dto.setTraceId(vo.getTraceId());
        dto.setQuery(vo.getQuery());
        dto.setAnswerable(vo.getAnswerable());
        dto.setConfidence(vo.getConfidence());
        dto.setConsultable(vo.getConsultable());
        dto.setRefusalReason(vo.getRefusalReason());
        dto.setAnswer(vo.getAnswer());
        dto.setClaimFail(vo.getClaimFail());
        dto.setElapsedMs(vo.getElapsedMs());
        // 证据列表映射
        List<EvidenceItemDTO> evidence = new ArrayList<>();
        if (vo.getEvidence() != null) {
            for (EvidenceEvaluateRespVO.EvidenceItemVO item : vo.getEvidence()) {
                EvidenceItemDTO d = new EvidenceItemDTO();
                d.setChunkId(item.getChunkId());
                d.setContent(item.getContent());
                d.setDocumentName(item.getDocumentName());
                d.setVersionNo(item.getVersionNo());
                d.setScore(item.getScore());
                d.setChannels(item.getChannels() != null ? new ArrayList<>(item.getChannels()) : null);
                evidence.add(d);
            }
        }
        dto.setEvidence(evidence);
        // 冲突列表映射
        List<EvidenceConflictDTO> conflicts = new ArrayList<>();
        if (vo.getConflicts() != null) {
            for (EvidenceEvaluateRespVO.ConflictVO c : vo.getConflicts()) {
                EvidenceConflictDTO d = new EvidenceConflictDTO();
                d.setEvidenceIndexA(c.getEvidenceIndexA());
                d.setEvidenceIndexB(c.getEvidenceIndexB());
                d.setReason(c.getReason());
                conflicts.add(d);
            }
        }
        dto.setConflicts(conflicts);
        // 逐句断言验证结果映射
        List<EvidenceClaimDTO> claims = new ArrayList<>();
        if (vo.getClaims() != null) {
            for (EvidenceEvaluateRespVO.ClaimVO c : vo.getClaims()) {
                EvidenceClaimDTO d = new EvidenceClaimDTO();
                d.setText(c.getText());
                d.setVerdict(c.getVerdict());
                d.setEvidenceIndex(c.getEvidenceIndex());
                claims.add(d);
            }
        }
        dto.setClaims(claims);
        return success(dto);
    }

}
