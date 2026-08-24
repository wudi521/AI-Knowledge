package cn.iocoder.yudao.module.evidence.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceConflictDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceSlotValueDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceAnalysisDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceChannelStatDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.EvidenceService;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
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
                req.getTenantId(), req.getUserId(), req.getHistory(), req.getSkipSlotDetection(), req.getTraceId(),
                req.getDomainCode(), req.getContextResolutionJson());
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
        dto.setVerificationDegraded(vo.getVerificationDegraded());
        dto.setTimedOut(vo.getTimedOut());
        dto.setStages(vo.getStages());
        dto.setElapsedMs(vo.getElapsedMs());
        // 证据列表映射
        List<EvidenceItemDTO> evidence = new ArrayList<>();
        if (vo.getEvidence() != null) {
            for (EvidenceEvaluateRespVO.EvidenceItemVO item : vo.getEvidence()) {
                EvidenceItemDTO d = new EvidenceItemDTO();
                d.setEvidenceId(item.getEvidenceId());
                d.setChunkId(item.getChunkId());
                d.setContent(item.getContent());
                d.setDocumentName(item.getDocumentName());
                d.setVersionNo(item.getVersionNo());
                d.setVersionId(item.getVersionId());
                d.setDocumentId(item.getDocumentId());
                d.setKbId(item.getKbId());
                d.setDomainCode(item.getDomainCode());
                d.setSectionType(item.getSectionType());
                d.setSectionTitle(item.getSectionTitle());
                d.setClaimNo(item.getClaimNo());
                d.setPageStart(item.getPageStart());
                d.setPageEnd(item.getPageEnd());
                d.setApplicationNo(item.getApplicationNo());
                d.setPublicationNo(item.getPublicationNo());
                d.setScore(item.getScore());
                d.setEvidenceType(item.getEvidenceType());
                d.setMetric(item.getMetric());
                d.setAggregateValue(item.getAggregateValue());
                d.setFilters(item.getFilters());
                d.setChannels(item.getChannels() != null ? new ArrayList<>(item.getChannels()) : null);
                d.setChunkMetadata(item.getChunkMetadata()); // 内部字段, 不对外展示
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
        // 槽位检测结果映射(缺槽位反问: 供对话层展示/后续合并)
        dto.setSlotKbId(vo.getSlotKbId());
        dto.setExtractedSlots(toSlotValueDTOList(vo.getExtractedSlots()));
        dto.setMissingSlots(toSlotValueDTOList(vo.getMissingSlots()));
        dto.setClarifyQuestion(vo.getClarifyQuestion());
        // 检索诊断透传(意图/实体/改写/通道统计; 供前端检索测试页单接口展示)
        dto.setAnalysis(toAnalysisDTO(vo.getAnalysis()));
        dto.setChannels(toChannelStatDTO(vo.getChannels()));
        // 检索路由透传(Query Planner 权威产出; 供对话层直接使用, 不自行推断)
        dto.setRoute(vo.getRoute());
        // 意图透传(如 STRUCTURED_AGGREGATE; 聚合等确定性路径)
        dto.setIntent(vo.getIntent());
        // 上下文回显(evidence-api ChatTurnDTO, 与 VO 同类型, 直接透传)
        dto.setHistory(vo.getHistory());
        dto.setStructuredResult(vo.getStructuredResult()); // CQ-02/03 结构化结果回流
        return success(dto);
    }

    /** 槽位值 VO → DTO 列表映射(null 安全) */
    private List<EvidenceSlotValueDTO> toSlotValueDTOList(List<EvidenceEvaluateRespVO.SlotValueVO> list) {
        List<EvidenceSlotValueDTO> result = new ArrayList<>();
        if (list != null) {
            for (EvidenceEvaluateRespVO.SlotValueVO vo : list) {
                EvidenceSlotValueDTO d = new EvidenceSlotValueDTO();
                d.setCode(vo.getCode());
                d.setName(vo.getName());
                d.setValue(vo.getValue());
                result.add(d);
            }
        }
        return result;
    }

    /** 语义分析 VO → DTO 映射(null 安全; 跨模块 DTO 独立) */
    private EvidenceAnalysisDTO toAnalysisDTO(RetrievalSearchRespDTO.RetrievalAnalysisDTO vo) {
        if (vo == null) {
            return null;
        }
        EvidenceAnalysisDTO dto = new EvidenceAnalysisDTO();
        dto.setIntent(vo.getIntent());
        dto.setEntities(vo.getEntities());
        dto.setRewrites(vo.getRewrites());
        dto.setSubQuestions(vo.getSubQuestions());
        dto.setSuccess(vo.getSuccess());
        dto.setRoute(vo.getRoute());
        return dto;
    }

    /** 通道统计 VO → DTO 映射(null 安全) */
    private EvidenceChannelStatDTO toChannelStatDTO(RetrievalSearchRespDTO.RetrievalChannelStatDTO vo) {
        if (vo == null) {
            return null;
        }
        EvidenceChannelStatDTO dto = new EvidenceChannelStatDTO();
        dto.setBm25(vo.getBm25());
        dto.setVector(vo.getVector());
        dto.setFused(vo.getFused());
        return dto;
    }

}
