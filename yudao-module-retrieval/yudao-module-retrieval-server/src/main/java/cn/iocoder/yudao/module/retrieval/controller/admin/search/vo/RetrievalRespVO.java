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

    /** 大模型总结回答(基于 TopN 证据生成, 带 [C1][C2] 引用; 生成失败或产品不匹配为 null) */
    private String answer;

    /** 产品/品牌一致性门禁: true=拒绝作答(代码判定, 不依赖 LLM 提示词) */
    private Boolean answerBlocked;

    /** 拒绝作答原因(产品不匹配时) */
    private String answerReason;

    /** TopN 结果 */
    private List<ResultVO> results;

    @Data
    public static class AnalysisVO {

        /** 意图: WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER */
        private String intent;

        /** 关键实体 */
        private List<String> entities;

        /** 涉及的产品/品牌(品牌一致性校验用, 无则空) */
        private List<String> products;

        /** 改写变体 */
        private List<String> rewrites;

        /** 子问题 */
        private List<String> subQuestions;

        /** 分析是否成功 */
        private boolean success;

        /** 查询路由: RULE / EXACT_METADATA / EXACT_CLAIM / SCOPED_RAG / HYBRID_RAG / ABSTAIN */
        private String route;
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

        /** 片段元数据(JSON; 专利来源卡片字段) */
        private String chunkMetadata;

        /** 父块编号(父子检索扩展: 子块命中回带父块上下文; 无则 null) */
        private Long contextChunkId;

        /** 父块内容(上下文, 截断防撑爆; 无则 null) */
        private String contextContent;
    }

}
