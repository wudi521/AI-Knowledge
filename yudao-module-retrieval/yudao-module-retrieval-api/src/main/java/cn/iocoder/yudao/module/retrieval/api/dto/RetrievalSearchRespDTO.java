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

    /** 精确/列表型检索的真实总命中数；普通检索可为空。用于避免把 TopK 数误报成全集数量。 */
    private Long totalHits;

    @Data
    public static class RetrievalAnalysisDTO {
        private String intent;
        private List<String> entities;
        private List<String> rewrites;
        private List<String> subQuestions;
        private Boolean success;
        /** 外部主路由保持 RULE/EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN 兼容集合。 */
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
