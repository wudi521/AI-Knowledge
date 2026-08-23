package cn.iocoder.yudao.module.retrieval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import cn.iocoder.yudao.module.retrieval.service.search.SearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 检索平台 对外 RPC 实现
 */
@Slf4j
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class RetrievalApiImpl implements RetrievalApi {

    @Resource
    private SearchService searchService;

    @Override
    public CommonResult<RetrievalSearchRespDTO> search(RetrievalSearchReqDTO req) {
        RetrievalRespVO vo = searchService.search(req.getQuery(), req.getKbIds(), req.getTopK(),
                req.getTenantId(), req.getUserId(), req.getHistory());
        // 映射 RetrievalRespVO -> RetrievalSearchRespDTO
        RetrievalSearchRespDTO dto = new RetrievalSearchRespDTO();
        dto.setQuery(vo.getQuery());
        dto.setAnswer(vo.getAnswer());
        dto.setAnswerBlocked(vo.getAnswerBlocked());
        dto.setAnswerReason(vo.getAnswerReason());
        // 结果映射
        List<RetrievalResultDTO> results = new ArrayList<>();
        if (vo.getResults() != null) {
            for (RetrievalRespVO.ResultVO r : vo.getResults()) {
                RetrievalResultDTO item = new RetrievalResultDTO();
                item.setChunkId(r.getChunkId());
                item.setContent(r.getContent());
                item.setDocumentId(r.getDocumentId());
                item.setDocumentName(r.getDocumentName());
                item.setVersionNo(r.getVersionNo());
                item.setRrfScore(r.getRrfScore());
                item.setRerankScore(r.getRerankScore());
                item.setChannels(r.getChannels());
                item.setChunkMetadata(r.getChunkMetadata());
                results.add(item);
            }
        }
        dto.setResults(results);
        // 问题涉及的产品/品牌(证据充分性判定用)
        dto.setQuestionProducts(vo.getAnalysis() != null ? vo.getAnalysis().getProducts() : null);
        // 语义分析意图(OUT_OF_SCOPE 显式透出, 供证据/聊天/前端识别超范围)
        dto.setIntent(vo.getAnalysis() != null ? vo.getAnalysis().getIntent() : null);
        // 语义分析详情 + 通道统计(前端检索诊断 / 证据评估透传; 双回答者收敛后由评估接口统一对外)
        if (vo.getAnalysis() != null) {
            RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
            analysis.setIntent(vo.getAnalysis().getIntent());
            analysis.setEntities(vo.getAnalysis().getEntities());
            analysis.setRewrites(vo.getAnalysis().getRewrites());
            analysis.setSubQuestions(vo.getAnalysis().getSubQuestions());
            analysis.setSuccess(vo.getAnalysis().isSuccess());
            analysis.setRoute(vo.getAnalysis().getRoute());
            dto.setAnalysis(analysis);
        }
        if (vo.getChannels() != null) {
            RetrievalSearchRespDTO.RetrievalChannelStatDTO channels = new RetrievalSearchRespDTO.RetrievalChannelStatDTO();
            channels.setBm25(vo.getChannels().getBm25());
            channels.setVector(vo.getChannels().getVector());
            channels.setFused(vo.getChannels().getFused());
            dto.setChannels(channels);
        }
        return success(dto);
    }

}
