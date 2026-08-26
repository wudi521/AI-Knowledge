package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

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
        /** 核心检索流程是否完成；false 表示不能把空结果解释成正常零命中。 */
        private Boolean success;
        /** 任一 Scope/Recall/Fusion/Rerank 插件发生能力降级。可有结果，但证明强度下降。 */
        private Boolean degraded;
        /** hard scope 是否被确定性门禁收敛为空；这与基础设施失败、普通零命中是不同状态。 */
        private Boolean blocked;
        /** Scope 阻断原因，仅用于执行事实/诊断，不由回答层重新解释。 */
        private String blockReason;
        private String route;
        private List<QueryStageTimingDTO> stages;
    }

    @Data
    public static class RetrievalChannelStatDTO {
        /** 兼容旧前端/指标。 */
        private Integer bm25;
        /** 兼容旧前端/指标。 */
        private Integer vector;
        private Integer fused;
        /** 通用 Recall 插件统计：channel -> 去重命中数。 */
        private Map<String, Integer> recall;
    }
}
