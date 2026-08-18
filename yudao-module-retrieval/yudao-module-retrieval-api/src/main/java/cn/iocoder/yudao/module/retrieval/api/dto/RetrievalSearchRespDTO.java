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

}
