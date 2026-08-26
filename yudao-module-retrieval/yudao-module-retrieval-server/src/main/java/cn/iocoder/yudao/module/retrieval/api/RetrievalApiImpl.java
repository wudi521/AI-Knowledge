package cn.iocoder.yudao.module.retrieval.api;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchMode;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import cn.iocoder.yudao.module.retrieval.service.search.ExactTextRetrievalService;
import cn.iocoder.yudao.module.retrieval.service.search.PlannedSearchService;
import cn.iocoder.yudao.module.retrieval.service.search.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 检索平台对外 RPC 实现。
 *
 * <p>显式 searchMode 只能进入对应的新执行链；只有 searchMode 为空才允许走 Legacy SearchService。
 * 未知非空 mode 必须 fail-closed，禁止因为拼写/版本错误重新触发旧 QueryAnalysis。</p>
 */
@Slf4j
@RestController
@Validated
public class RetrievalApiImpl implements RetrievalApi {

    private final SearchService legacySearchService;
    private final ExactTextRetrievalService exactTextRetrievalService;
    private final PlannedSearchService plannedSearchService;

    public RetrievalApiImpl(SearchService legacySearchService,
                            ExactTextRetrievalService exactTextRetrievalService,
                            PlannedSearchService plannedSearchService) {
        this.legacySearchService = legacySearchService;
        this.exactTextRetrievalService = exactTextRetrievalService;
        this.plannedSearchService = plannedSearchService;
    }

    @Override
    public CommonResult<RetrievalSearchRespDTO> search(RetrievalSearchReqDTO req) {
        if (req == null) {
            return CommonResult.error(BAD_REQUEST.getCode(), "retrieval request must not be null");
        }
        String rawMode = req.getSearchMode();
        if (StrUtil.isBlank(rawMode)) {
            return success(searchLegacy(req));
        }
        RetrievalSearchMode mode = RetrievalSearchMode.parseExplicit(rawMode).orElse(null);
        if (mode == null) {
            return CommonResult.error(BAD_REQUEST.getCode(), "unsupported searchMode: " + rawMode);
        }
        return switch (mode) {
            case EXACT_TEXT_SEARCH -> success(exactTextRetrievalService.search(req));
            case PLANNED_HYBRID -> success(plannedSearchService.search(req));
        };
    }

    /** 兼容旧入口；新 Agent/领域能力禁止进入本分支。 */
    private RetrievalSearchRespDTO searchLegacy(RetrievalSearchReqDTO req) {
        RetrievalRespVO vo = legacySearchService.search(req.getQuery(), req.getKbIds(), req.getTopK(),
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
        return dto;
    }
}
