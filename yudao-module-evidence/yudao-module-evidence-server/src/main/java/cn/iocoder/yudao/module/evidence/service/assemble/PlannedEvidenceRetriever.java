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

    /** 迁移兼容：旧调用方没有显式领域时由 Retrieval 侧按 KB Registry 解析。 */
    public Result search(String query, List<String> variants, List<Long> kbIds, List<Long> documentIds,
                         Integer topK, Long tenantId, Long userId, String traceId) {
        return search(query, variants, kbIds, documentIds, topK, tenantId, userId, null, traceId);
    }

    /**
     * Planned semantic retrieval。domainCode 若已由 Agent Runtime 确认则直接透传，
     * Retrieval 不再为了插件选择重复反查 Knowledge Registry。
     */
    public Result search(String query, List<String> variants, List<Long> kbIds, List<Long> documentIds,
                         Integer topK, Long tenantId, Long userId, String domainCode, String traceId) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(query);
        req.setQueryVariants(variants);
        req.setKbIds(kbIds);
        req.setDocumentIds(documentIds);
        req.setTopK(topK == null ? 8 : topK);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setDomainCode(domainCode);
        req.setTraceId(traceId);
        req.setSearchMode("PLANNED_HYBRID");
        return execute(req);
    }

    /** 迁移兼容：旧调用方没有显式领域时保持原行为。 */
    public Result exactText(String exactText, List<Long> kbIds, List<Long> documentIds,
                            Integer topK, Long tenantId, Long userId, String traceId) {
        return exactText(exactText, kbIds, documentIds, topK, tenantId, userId, null, traceId);
    }

    /** Exact text retrieval，同样透传上游已经确认的领域作用域。 */
    public Result exactText(String exactText, List<Long> kbIds, List<Long> documentIds,
                            Integer topK, Long tenantId, Long userId, String domainCode, String traceId) {
        RetrievalSearchReqDTO req = new RetrievalSearchReqDTO();
        req.setQuery(exactText);
        req.setExactText(exactText);
        req.setKbIds(kbIds);
        req.setDocumentIds(documentIds);
        req.setTopK(topK == null ? 20 : topK);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setDomainCode(domainCode);
        req.setTraceId(traceId);
        req.setSearchMode("EXACT_TEXT_SEARCH");
        return execute(req);
    }

    private Result execute(RetrievalSearchReqDTO req) {
        try {
            CommonResult<RetrievalSearchRespDTO> response = retrievalApi.search(req);
            if (response == null) {
                return Result.failed("retrieval RPC returned null response");
            }
            if (response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
                return Result.failed("retrieval RPC failed with code=" + response.getCode());
            }
            RetrievalSearchRespDTO data = response.getData();
            List<RetrievalResultDTO> rows = data.getResults() == null ? Collections.emptyList() : data.getResults();
            RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis = data.getAnalysis();
            boolean pipelineFailed = analysis != null && Boolean.FALSE.equals(analysis.getSuccess());
            boolean degradedEmpty = rows.isEmpty() && analysis != null && Boolean.TRUE.equals(analysis.getDegraded());
            if (rows.isEmpty() && (pipelineFailed || degradedEmpty)) {
                return new Result(Status.FAILED,
                        degradedEmpty ? "retrieval pipeline degraded with no reliable matches"
                                : "retrieval pipeline reported execution failure",
                        List.of(), analysis, data.getChannels(), data.getTotalHits(),
                        data.getTotalHitsExact(), data.getCandidateTotalHits());
            }
            boolean scopeBlocked = rows.isEmpty() && analysis != null && Boolean.TRUE.equals(analysis.getBlocked());
            if (scopeBlocked) {
                return new Result(Status.BLOCKED,
                        analysis.getBlockReason() == null ? "hard scope resolved to empty set" : analysis.getBlockReason(),
                        List.of(), analysis, data.getChannels(), data.getTotalHits(),
                        data.getTotalHitsExact(), data.getCandidateTotalHits());
            }

            List<Double> raw = new ArrayList<>(rows.size());
            for (RetrievalResultDTO row : rows) {
                if (row.getRerankScore() != null && row.getRerankScore() >= 0) {
                    raw.add(row.getRerankScore().doubleValue());
                } else {
                    raw.add(row.getRrfScore() == null ? 0D : row.getRrfScore());
                }
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
            Status status = evidences.isEmpty() ? Status.EMPTY : Status.MATCHES;
            return new Result(status, null, evidences, analysis, data.getChannels(),
                    data.getTotalHits(), data.getTotalHitsExact(), data.getCandidateTotalHits());
        } catch (Exception e) {
            log.warn("[planned-evidence][retrieval failed: {}]", e.getMessage());
            return Result.failed("retrieval source failure: " + e.getClass().getSimpleName());
        }
    }

    public enum Status {
        MATCHES,
        EMPTY,
        BLOCKED,
        FAILED
    }

    public record Result(Status status,
                         String errorMessage,
                         List<Evidence> evidences,
                         RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                         RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                         Long totalHits,
                         Boolean totalHitsExact,
                         Long candidateTotalHits) {
        public Result {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            status = status == null ? (evidences.isEmpty() ? Status.EMPTY : Status.MATCHES) : status;
        }

        /**
         * 迁移兼容构造器：旧单测/调用点没有 status 时，由 evidence 是否为空推导合法 MATCHES/EMPTY。
         * FAILED/BLOCKED 必须显式构造，避免把基础设施故障或 hard-scope 阻断伪装成普通零命中。
         */
        public Result(List<Evidence> evidences,
                      RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis,
                      RetrievalSearchRespDTO.RetrievalChannelStatDTO channels,
                      Long totalHits,
                      Boolean totalHitsExact) {
            this(evidences == null || evidences.isEmpty() ? Status.EMPTY : Status.MATCHES,
                    null, evidences, analysis, channels, totalHits, totalHitsExact, null);
        }

        public static Result failed(String message) {
            return new Result(Status.FAILED, message, List.of(), null, null, null, null, null);
        }

        public boolean failed() {
            return status == Status.FAILED;
        }

        public boolean blocked() {
            return status == Status.BLOCKED;
        }

        public boolean empty() {
            return status == Status.EMPTY || status == Status.BLOCKED;
        }
    }
}
