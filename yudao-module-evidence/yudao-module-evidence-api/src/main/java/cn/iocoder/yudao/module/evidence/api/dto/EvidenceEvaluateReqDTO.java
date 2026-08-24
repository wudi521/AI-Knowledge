package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 证据评估 RPC 请求 DTO(对话等模块通过 Feign 调用)
 */
@Data
public class EvidenceEvaluateReqDTO {

    /** 评估问题 */
    private String query;

    /** 限定知识库编号列表(空 = 全部可见知识库) */
    private List<Long> kbIds;

    /** 证据条数(空则默认 8) */
    private Integer topK;

    /** 租户编号(RPC 无登录态, 显式传递) */
    private Long tenantId;

    /** 用户编号(权限过滤用) */
    private Long userId;

    /** 上下文轮次(可选, 空 = 单轮) */
    private List<ChatTurnDTO> history;

    /** 跳过槽位检测(评测/批处理用: 测检索+回答质量, 不走对话层反问门) */
    private Boolean skipSlotDetection;

    /** 知识库领域编码(如 PATENT; 会话绑定 KB 的领域, 结构化查询路由用) */
    private String domainCode;

    /** P0-09: 统一主 traceId(q- 前缀, 对话层下发; 贯穿检索/证据全链路) */
    private String traceId;

    /** CQ-04~10: chat 侧已消解的多轮上下文(JSON: QueryContextResolution 的 explicitEntityIds/fieldCodeHint 等) */
    private String contextResolutionJson;

    /** CQ-02/38: Composite Query Plan 预算(对话层配置下发; null 字段证据侧默认值兜底) */
    private QueryPlanBudgetDTO planBudget;

}
