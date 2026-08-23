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

    /** 是否验证降级(验证器解析故障重试耗尽; 回答未完整验证) */
    private Boolean verificationDegraded;

    /** 是否查询超时(整体 Deadline 触发; 停止继续 repair, 返回降级结果) */
    private Boolean timedOut;

    /** P0-09: 全链路阶段时序(检索阶段 + 证据阶段; 汇聚统一主 traceId 下) */
    private List<cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO> stages;

    /** 评估耗时(ms, 不含落库) */
    private Integer elapsedMs;

    /** 回显本次使用的上下文(供落库快照/前端展示) */
    private List<ChatTurnDTO> history;

    /** 槽位检测: 参与检测的知识库编号(多库并集取首个; 未检测为 null) */
    private Long slotKbId;

    /** 槽位检测: 抽取的槽位值列表(审计/后续合并用) */
    private List<EvidenceSlotValueDTO> extractedSlots;

    /** 槽位检测: 缺失的必填槽位列表(value 恒为 null) */
    private List<EvidenceSlotValueDTO> missingSlots;

    /** 槽位检测: 反问句(缺必填槽位时填充) */
    private String clarifyQuestion;

    /** 语义分析详情(意图/实体/改写/子问题; 透传检索结果, 供前端检索诊断) */
    private EvidenceAnalysisDTO analysis;

    /** 检索路由(Query Planner 权威产出: EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN) */
    private String route;

    /** 通道召回统计(BM25/向量/融合; 供前端检索诊断) */
    private EvidenceChannelStatDTO channels;

}
