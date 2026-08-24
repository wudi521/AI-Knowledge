package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.planner.CompletenessPolicy;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * EXACT_TEXT_SEARCH 执行层：候选检索 + 原文逐字校验后的确定性回答。
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

    public record Result(String answer, List<Evidence> evidences, boolean answerable,
                         String reasonCode, Long totalHits, boolean truncated) {}

    public Result execute(String query, String exactText, Long kbId, List<Long> documentIds,
                          Long tenantId, Long userId, String traceId) {
        return execute(query, exactText, kbId, documentIds, tenantId, userId, traceId,
                requiresCompleteResult(query) ? CompletenessPolicy.COMPLETE_REQUIRED : CompletenessPolicy.TOP_K_ALLOWED);
    }

    public Result execute(String query, String exactText, Long kbId, List<Long> documentIds,
                          Long tenantId, Long userId, String traceId, CompletenessPolicy completenessPolicy) {
        if (kbId == null || StrUtil.isBlank(exactText)) {
            return new Result(null, List.of(), false, "MISSING_EXACT_TEXT", 0L, false);
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
            boolean exactTotalKnown = data != null && Boolean.TRUE.equals(data.getTotalHitsExact());
            Long exactTotal = exactTotalKnown ? data.getTotalHits() : null;
            Long candidateTotal = data != null ? data.getCandidateTotalHits() : null;

            // 只有完整检查了候选集，才能确定“0 个逐字命中”。
            if (rows.isEmpty()) {
                if (exactTotalKnown && (exactTotal == null || exactTotal == 0L)) {
                    return new Result("未在当前查询范围的已发布原文中找到精确短语「" + exactText + "」。",
                            List.of(), true, null, 0L, false);
                }
                return new Result(null, List.of(), false, "EXACT_TEXT_COMPLETENESS_UNKNOWN", null, true);
            }

            List<Evidence> evidences = rows.stream().map(this::toEvidence).toList();
            if (!exactTotalKnown) {
                String answer = renderUnknownTotal(exactText, rows, candidateTotal, completenessPolicy);
                String reasonCode = completenessPolicy == CompletenessPolicy.COMPLETE_REQUIRED
                        ? "EXACT_TEXT_RESULT_TRUNCATED" : null;
                return new Result(answer, evidences, true, reasonCode, null, true);
            }

            long totalHits = exactTotal == null ? rows.size() : exactTotal;
            boolean truncated = totalHits > rows.size();
            String answer = renderKnownTotal(exactText, rows, totalHits, truncated, completenessPolicy);
            String reasonCode = truncated && completenessPolicy == CompletenessPolicy.COMPLETE_REQUIRED
                    ? "EXACT_TEXT_RESULT_TRUNCATED" : null;
            return new Result(answer, evidences, true, reasonCode, totalHits, truncated);
        } catch (Exception e) {
            log.warn("[execute][EXACT_TEXT_SEARCH 失败, phrase={}, error={}]", exactText, e.getMessage());
            return new Result(null, List.of(), false, "EXACT_TEXT_RETRIEVAL_FAILED", null, false);
        }
    }

    private boolean requiresCompleteResult(String query) {
        if (StrUtil.isBlank(query)) return false;
        if (StrUtil.containsAny(query, "全部", "所有", "哪些", "哪里", "哪些地方", "哪些文档", "哪些专利",
                "列出", "列举", "分别", "多少", "几处", "几条", "出现在哪", "出现于哪些")) return true;
        return !StrUtil.containsAny(query, "有没有", "是否", "有吗", "存在吗", "出现过吗", "包含吗");
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

    private String renderKnownTotal(String exactText, List<RetrievalResultDTO> rows, long totalHits,
                                    boolean truncated, CompletenessPolicy completenessPolicy) {
        StringBuilder answer = new StringBuilder();
        answer.append("在已发布原文中找到精确短语「").append(exactText).append("」，逐字精确命中 ")
                .append(totalHits).append(" 个片段");
        if (truncated) {
            answer.append("，当前仅展示前 ").append(rows.size()).append(" 个");
            if (completenessPolicy == CompletenessPolicy.COMPLETE_REQUIRED) {
                answer.append("。当前展示被截断，不能把下列片段视为完整清单");
            }
        }
        appendRows(answer, exactText, rows);
        return answer.toString().trim();
    }

    private String renderUnknownTotal(String exactText, List<RetrievalResultDTO> rows, Long candidateTotal,
                                      CompletenessPolicy completenessPolicy) {
        StringBuilder answer = new StringBuilder();
        answer.append("已确认已发布原文中存在逐字精确短语「").append(exactText).append("」");
        if (candidateTotal != null) {
            answer.append("。ES 短语候选共有 ").append(candidateTotal)
                    .append(" 个，已超过单次逐字校验上限，因此不能可靠给出精确总数");
        } else {
            answer.append("，但当前无法可靠计算完整精确总数");
        }
        if (completenessPolicy == CompletenessPolicy.COMPLETE_REQUIRED) {
            answer.append("；本问题要求完整清单，请缩小知识库、文档或其它查询范围后重试");
        } else {
            answer.append("；下面展示已逐字确认的前 ").append(rows.size()).append(" 个片段");
        }
        appendRows(answer, exactText, rows);
        return answer.toString().trim();
    }

    private void appendRows(StringBuilder answer, String exactText, List<RetrievalResultDTO> rows) {
        answer.append("：\n");
        int index = 1;
        for (RetrievalResultDTO row : rows) {
            answer.append(index++).append(". ")
                    .append(StrUtil.blankToDefault(row.getDocumentName(), "文档" + row.getDocumentId()));
            String snippet = snippet(row.getContent(), exactText);
            if (StrUtil.isNotBlank(snippet)) answer.append("：").append(snippet);
            answer.append('\n');
        }
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
