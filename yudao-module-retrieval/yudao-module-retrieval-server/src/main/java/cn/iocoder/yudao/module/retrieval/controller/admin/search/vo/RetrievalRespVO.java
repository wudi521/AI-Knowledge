package cn.iocoder.yudao.module.retrieval.controller.admin.search.vo;

import lombok.Data;

import java.util.List;

/**
 * 检索响应 VO
 */
@Data
public class RetrievalRespVO {

    /** 原始问题 */
    private String query;

    /** 语义分析(意图/实体/改写/子问题) */
    private AnalysisVO analysis;

    /** 各通道召回数 */
    private ChannelStatVO channels;

    /** TopN 结果 */
    private List<ResultVO> results;

    @Data
    public static class AnalysisVO {

        /** 意图: WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER */
        private String intent;

        /** 关键实体 */
        private List<String> entities;

        /** 改写变体 */
        private List<String> rewrites;

        /** 子问题 */
        private List<String> subQuestions;

        /** 分析是否成功 */
        private boolean success;
    }

    @Data
    public static class ChannelStatVO {

        /** BM25 召回数 */
        private int bm25;

        /** 向量召回数 */
        private int vector;

        /** 融合后候选数 */
        private int fused;
    }

    @Data
    public static class ResultVO {

        /** 片段编号 */
        private Long chunkId;

        /** 片段内容 */
        private String content;

        /** 来源文档编号 */
        private Long documentId;

        /** 来源文档名 */
        private String documentName;

        /** 版本号: V1/V2/... */
        private String versionNo;

        /** RRF 融合分 */
        private Double rrfScore;

        /** 重排分 */
        private Float rerankScore;

        /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"] */
        private List<String> channels;
    }

}
