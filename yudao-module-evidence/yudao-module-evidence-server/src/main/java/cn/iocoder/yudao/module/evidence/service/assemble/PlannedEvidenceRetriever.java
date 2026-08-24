package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Query Engine V3 的 planned retrieval 适配器；不触发 Retrieval 内部 QueryAnalysis。 */
@Slf4j
@Component
public class PlannedEvidenceRetriever {

    private final RetrievalApi retrievalApi;

    public PlannedEvidenceRetriever(RetrievalApi retrievalApi) {
        this.retrievalApi = retrievalApi;
    }

    public Result search(String query, List<String> variants, List<Long> kbIds, List<Long> documentIds,
                         Integer topK, Long tenantId, Long userId, String traceId) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(query);
        req.setQueryVariants(variants);
        req.setKbIds(kbIds);
        req.setDocumentIds(documentIds);
        req.setTopK(topK == null ? 8 : topK);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setTraceId(traceId);
        req.setSearchMode("PLANNED_HYBRID");
        return execute(req);
    }

    public Result exactText(String exactText, List<Long> kbIds, List<Long> documentIds,
                            Integer topK, Long tenantId, Long userId, String traceId) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(exactText);
        req.setExactText(exactText);
        req.setKbIds(kbIds);
        req.setDocumentIds(documentIds);
        req.setTopK(topK == null ? 20 : topK);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setTraceId(traceId);
        req.setSearchMode("EXACT_TEXT_SEARCH");
        return execute(req);
    }

    private Result execute(RetrievalSearchReqDTO req) {
        try {
            CommonResult<RetrievalSearchRespDTO> response = retrievalApi.search(req);
            if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
                return Result.empty();
            }
            RetrievalSearchRespDTO data = response.getData();
            List<RetrievalResultDTO> rows = data.getResults() == null ? Collections.emptyList() : data.getResults();
            List<Double> raw = new ArrayList<>(rows.size());
            for (RetrievalResultDTO row : rows) {
                if (row.getRerankScore() != null && row.getRerankScore() >= 0) raw.add(row.getRerankScore().doubleValue());
                else raw.add(row.getRrfScore());
            }
            List<Double> normalized = EvidenceSimilarity.minMaxNormalize(raw);
            List<Evidence> evidences = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                RetrievalResultDTO row = rows.get(i);
                evidences.add(Evidence.builder()
                        .chunkId(row.getChunkId())
                        .content(row.getContent())
                        .documentId(row.getDocumentId() == null ? null : String.valueOf(row.getDocumentId()))
                        .documentName(row.getDocumentName())
                        .versionNo(row.getVersionNo())
                        .versionId(row.getVersionId())
                        .score(normalized.get(i))
                        .rawScore(raw.get(i))
                        .products(Collections.emptyList())
                        .channels(row.getChannels() == null ? new ArrayList<>() : new ArrayList<>(row.getChannels()))
                        .chunkMetadata(row.getChunkMetadata())
                        .build());
            }
            return new Result(evidences, data.getAnalysis(), data.getChannels(), data.getTotalHits(), data.getTotalHitsExact());
        } catch (Exception e) {
            log.warn("[planned-evidence][retrieval failed: {}]", e.getMessage());
            return Result.empty();
        }
    }

    public record Result(List<Evidence> evidences,
                         RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                         RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                         Long totalHits,
                         Boolean totalHitsExact) {
        public static Result empty() { return new Result(List.of(), null, null, null, null); }
    }
}
