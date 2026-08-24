package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

import java.util.List;

/** 检索 RPC 响应 DTO */
@Data
public class RetrievalSearchRespDTO {

    private String query;
    private String answer;
    private Boolean answerBlocked;
    private String answerReason;
    private List<RetrievalResultDTO> results;
    private List<String> questionProducts;
    private String intent;
    private RetrievalAnalysisDTO analysis;
    private RetrievalChannelStatDTO channels;

    /**
     * 经过原文逐字二次校验后的真实总命中数；仅 totalHitsExact=true 时可用于全集结论。
     * 普通检索或候选集合过大无法完整校验时可为空。
     */
    private Long totalHits;

    /** totalHits 是否为完整、可证明的逐字精确总数。 */
    private Boolean totalHitsExact;

    /** ES match_phrase 候选总数，仅用于诊断/完整性判断，不能冒充原文逐字命中数。 */
    private Long candidateTotalHits;

    @Data
    public static class RetrievalAnalysisDTO {
        private String intent;
        private List<String> entities;
        private List<String> rewrites;
        private List<String> subQuestions;
        private Boolean success;
        private String route;
        private List<QueryStageTimingDTO> stages;
    }

    @Data
    public static class RetrievalChannelStatDTO {
        private Integer bm25;
        private Integer vector;
        private Integer fused;
    }
}
