package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * EXACT_TEXT_SEARCH 执行层：调用 Retrieval phrase-only 快路径并确定性渲染答案。
 * 0 QueryAnalysis LLM / 0 embedding / 0 vector / 0 rerank / 0 generate / 0 verify。
 */
@Slf4j
@Service
public class ExactTextExecutionService {

    private static final int DEFAULT_TOP_K = 20;

    private final RetrievalApi retrievalApi;

    public ExactTextExecutionService(RetrievalApi retrievalApi) {
        this.retrievalApi = retrievalApi;
    }

    public record Result(String answer, List<Evidence> evidences, boolean answerable, String reasonCode) {}

    public Result execute(String query, String exactText, Long kbId, List<Long> documentIds,
                          Long tenantId, Long userId, String traceId) {
        if (kbId == null || StrUtil.isBlank(exactText)) {
            return new Result(null, List.of(), false, "MISSING_EXACT_TEXT");
        }
        try {
            RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
            req.setQuery(query);
            req.setExactText(exactText);
            req.setSearchMode("EXACT_TEXT_SEARCH");
            req.setKbIds(List.of(kbId));
            req.setDocumentIds(documentIds == null || documentIds.isEmpty() ? null : documentIds);
            req.setTopK(DEFAULT_TOP_K);
            req.setTenantId(tenantId);
            req.setUserId(userId);
            req.setTraceId(traceId);
            CommonResult<RetrievalSearchRespDTO> rpc = retrievalApi.search(req);
            RetrievalSearchRespDTO data = rpc == null ? null : rpc.getCheckedData();
            List<RetrievalResultDTO> rows = data != null && data.getResults() != null ? data.getResults() : List.of();
            if (rows.isEmpty()) {
                return new Result("未在当前查询范围的已发布原文中找到精确短语「" + exactText + "」。",
                        List.of(), true, null);
            }
            List<Evidence> evidences = rows.stream().map(this::toEvidence).toList();
            return new Result(render(exactText, rows), evidences, true, null);
        } catch (Exception e) {
            log.warn("[execute][EXACT_TEXT_SEARCH 失败, phrase={}, error={}]", exactText, e.getMessage());
            return new Result(null, List.of(), false, "EXACT_TEXT_RETRIEVAL_FAILED");
        }
    }

    private Evidence toEvidence(RetrievalResultDTO r) {
        return Evidence.builder()
                .chunkId(r.getChunkId())
                .content(r.getContent())
                .documentId(r.getDocumentId() == null ? null : String.valueOf(r.getDocumentId()))
                .documentName(r.getDocumentName())
                .versionNo(r.getVersionNo())
                .versionId(r.getVersionId())
                .score(r.getRrfScore())
                .rawScore(r.getRrfScore())
                .products(List.of())
                .channels(r.getChannels() == null ? List.of("exact_text") : r.getChannels())
                .chunkMetadata(r.getChunkMetadata())
                .build();
    }

    private String render(String exactText, List<RetrievalResultDTO> rows) {
        StringBuilder answer = new StringBuilder();
        answer.append("在已发布原文中找到精确短语「").append(exactText).append("」，共命中 ")
                .append(rows.size()).append(" 个片段：\n");
        int index = 1;
        for (RetrievalResultDTO row : rows) {
            answer.append(index++).append(". ");
            answer.append(StrUtil.blankToDefault(row.getDocumentName(), "文档" + row.getDocumentId()));
            String snippet = snippet(row.getContent(), exactText);
            if (StrUtil.isNotBlank(snippet)) answer.append("：").append(snippet);
            answer.append('\n');
        }
        return answer.toString().trim();
    }

    private String snippet(String content, String phrase) {
        if (StrUtil.isBlank(content)) return null;
        int p = content.indexOf(phrase);
        if (p < 0) return StrUtil.maxLength(content.replaceAll("\\s+", " "), 160);
        int start = Math.max(0, p - 50);
        int end = Math.min(content.length(), p + phrase.length() + 80);
        String text = content.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + text + (end < content.length() ? "…" : "");
    }
}
