package cn.iocoder.yudao.module.retrieval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import cn.iocoder.yudao.module.retrieval.service.search.ExactTextRetrievalService;
import cn.iocoder.yudao.module.retrieval.service.search.PlannedSearchService;
import cn.iocoder.yudao.module.retrieval.service.search.SearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 检索平台 对外 RPC 实现 */
@Slf4j
@RestController
@Validated
public class RetrievalApiImpl implements RetrievalApi {

    @Resource private SearchService searchService;
    @Resource private ExactTextRetrievalService exactTextRetrievalService;
    @Resource private PlannedSearchService plannedSearchService;

    @Override
    public CommonResult<RetrievalSearchRespDTO> search(RetrievalSearchReqDTO req) {
        // Query Engine 显式快路径：精确原文只跑 ES match_phrase，禁止进入 QueryAnalysis/Vector/RRF/rerank。
        if (req != null && "EXACT_TEXT_SEARCH".equals(req.getSearchMode())) {
            return success(exactTextRetrievalService.search(req));
        }
        // V3 规划检索：自然语言已在 Evidence Query Engine 理解完毕，Retrieval 仅执行，不得二次 QueryAnalysis。
        if (req != null && "PLANNED_HYBRID".equals(req.getSearchMode())) {
            return success(plannedSearchService.search(req));
        }

        // 兼容旧入口；V3 稳定后再删除 SearchService 内部 QueryAnalysis。
        RetrievalRespVO vo = searchService.search(req.getQuery(), req.getKbIds(), req.getTopK(),
                req.getTenantId(), req.getUserId(), req.getHistory(), req.getTraceId(), req.getDocumentIds());
        RetrievalSearchRespDTO dto = new RetrievalSearchRespDTO();
        dto.setQuery(vo.getQuery());
        dto.setAnswer(vo.getAnswer());
        dto.setAnswerBlocked(vo.getAnswerBlocked());
        dto.setAnswerReason(vo.getAnswerReason());
        List<RetrievalResultDTO> results = new ArrayList<>();
        if (vo.getResults() != null) {
            for (RetrievalRespVO.ResultVO r : vo.getResults()) {
                RetrievalResultDTO item = new RetrievalResultDTO();
                item.setChunkId(r.getChunkId());
                item.setContent(r.getContent());
                item.setDocumentId(r.getDocumentId());
                item.setDocumentName(r.getDocumentName());
                item.setVersionNo(r.getVersionNo());
                item.setVersionId(r.getVersionId());
                item.setRrfScore(r.getRrfScore());
                item.setRerankScore(r.getRerankScore());
                item.setChannels(r.getChannels());
                item.setChunkMetadata(r.getChunkMetadata());
                results.add(item);
            }
        }
        dto.setResults(results);
        dto.setQuestionProducts(vo.getAnalysis() != null ? vo.getAnalysis().getProducts() : null);
        dto.setIntent(vo.getAnalysis() != null ? vo.getAnalysis().getIntent() : null);
        if (vo.getAnalysis() != null) {
            RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = new RetrievalSearchRespDTO.RetrievalAnalysisDTO();
            analysis.setIntent(vo.getAnalysis().getIntent());
            analysis.setEntities(vo.getAnalysis().getEntities());
            analysis.setRewrites(vo.getAnalysis().getRewrites());
            analysis.setSubQuestions(vo.getAnalysis().getSubQuestions());
            analysis.setSuccess(vo.getAnalysis().isSuccess());
            analysis.setRoute(vo.getAnalysis().getRoute());
            analysis.setStages(vo.getAnalysis().getStages());
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
