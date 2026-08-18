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

}
