package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 证据评估 RPC 响应 DTO(对话等模块通过 Feign 调用)
 */
@Data
public class EvidenceEvaluateRespDTO {

    /** 评估会话追踪号(ev- 前缀) */
    private String traceId;

    /** 原始问题 */
    private String query;

    /** 是否可作答(充分性判定) */
    private Boolean answerable;

    /** 证据充分度融合置信度(0~1) */
    private Double confidence;

    /** 是否可转人工咨询 */
    private Boolean consultable;

    /** 拒绝作答原因(answerable=false 时填充) */
    private String refusalReason;

    /** 去重后证据列表(按得分降序, 与 conflicts/claims 的索引一一对应) */
    private List<EvidenceItemDTO> evidence;

    /** 冲突列表(evidenceIndexA/B 为 evidence 列表位置索引) */
    private List<EvidenceConflictDTO> conflicts;

    /** 生成的回答(全部断言通过验证时非空) */
    private String answer;

    /** 逐句断言验证结果 */
    private List<EvidenceClaimDTO> claims;

    /** 是否验证失败(生成失败/重试耗尽 → true, 此时 answer=null) */
    private Boolean claimFail;

    /** 评估耗时(ms, 不含落库) */
    private Integer elapsedMs;

    /** 回显本次使用的上下文(供落库快照/前端展示) */
    private List<ChatTurnDTO> history;

}
