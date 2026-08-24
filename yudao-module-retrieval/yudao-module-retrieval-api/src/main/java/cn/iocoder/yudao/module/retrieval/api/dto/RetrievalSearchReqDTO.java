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

    /** CQ-38: 外部显式限定文档集(逐实体语义执行 PER_ENTITY_SEMANTIC); 非空时作为 hard scope, 禁止全库检索后过滤 */
    private List<Long> documentIds;

}
