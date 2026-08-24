package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 检索 RPC 请求 DTO(证据等模块通过 Feign 调用)
 */
@Data
public class RetrievalSearchReqDTO {

    /** 检索内容 */
    private String query;

    /** 限定知识库编号列表(空 = 全部可见知识库) */
    private List<Long> kbIds;

    /** 返回条数(默认 5, 最大 20) */
    private Integer topK;

    /** 租户编号(RPC 无登录态, 显式传递) */
    private Long tenantId;

    /** 用户编号(权限过滤用) */
    private Long userId;

    /** 上下文轮次(可选, 空 = 单轮) */
    private List<ChatTurnDTO> history;

    /** P0-09: 统一主 traceId(q- 前缀, 对话层下发; 贯穿检索/证据全链路) */
    private String traceId;

    /** CQ-38: 外部显式限定文档集; 非空时作为 hard scope */
    private List<Long> documentIds;

    /**
     * Planner 显式执行模式。当前仅允许受限值 EXACT_TEXT_SEARCH；空表示走既有 QueryAnalysis 路由。
     * 这是内部可信提示，不接受任意 ES DSL/SQL。
     */
    private String searchMode;

    /**
     * EXACT_TEXT_SEARCH 的目标原文短语。与 query 分离，避免把“原文包含/是否出现”等指令词送入 match_phrase。
     */
    private String exactText;

}
