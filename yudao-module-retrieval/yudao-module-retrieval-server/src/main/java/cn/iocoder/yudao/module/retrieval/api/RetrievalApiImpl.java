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
                results.add(item);
            }
        }
        dto.setResults(results);
        // 问题涉及的产品/品牌(证据充分性判定用)
        dto.setQuestionProducts(vo.getAnalysis() != null ? vo.getAnalysis().getProducts() : null);
        return success(dto);
    }

}
