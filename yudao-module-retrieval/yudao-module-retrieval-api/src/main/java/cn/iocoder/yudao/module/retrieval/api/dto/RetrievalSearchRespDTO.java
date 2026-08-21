package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 检索 RPC 响应 DTO
 */
@Data
public class RetrievalSearchRespDTO {

    /** 原始问题 */
    private String query;

    /** 大模型总结回答(生成失败或产品不匹配为 null) */
    private String answer;

    /** 产品/品牌一致性门禁: true=拒绝作答 */
    private Boolean answerBlocked;

    /** 拒绝作答原因 */
    private String answerReason;

    /** TopN 结果 */
    private List<RetrievalResultDTO> results;

    /** 问题涉及的产品/品牌(分析结果, 供证据充分性判定) */
    private List<String> questionProducts;

    /** 语义分析意图(动态意图: 知识库意图名 | OUT_OF_SCOPE; 无意图集回退路径为固定枚举) */
    private String intent;

    /** 语义分析详情(意图/实体/改写变体/子问题; 供前端检索诊断与证据评估透传) */
    private RetrievalAnalysisDTO analysis;

    /** 通道召回统计(BM25/向量/融合数量; 供前端检索诊断) */
    private RetrievalChannelStatDTO channels;

    /**
     * 语义分析详情 DTO(与 RetrievalRespVO.AnalysisVO 同构)
     */
    @Data
    public static class RetrievalAnalysisDTO {

        /** 意图分类(固定枚举或知识库意图名) */
        private String intent;

        /** 关键实体 */
        private List<String> entities;

        /** 改写变体 */
        private List<String> rewrites;

        /** 子问题 */
        private List<String> subQuestions;

        /** 分析是否成功(失败时走关键词检索) */
        private Boolean success;

    }

    /**
     * 通道召回统计 DTO(与 RetrievalRespVO.ChannelStatVO 同构)
     */
    @Data
    public static class RetrievalChannelStatDTO {

        /** BM25 通道召回数 */
        private Integer bm25;

        /** 向量通道召回数 */
        private Integer vector;

        /** RRF 融合数 */
        private Integer fused;

    }

}
