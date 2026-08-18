package cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import lombok.Data;

import java.util.List;

/**
 * 证据评估响应 VO
 * <p>
 * 语义约定:
 * <ul>
 *     <li>answerable=false 时 refusalReason 必填(证据不足/存在冲突/产品不匹配/检索阻断/评估异常等);</li>
 *     <li>claimFail=true 时 answer 恒为 null(生成失败/验证重试耗尽), claims 保留最后一次验证结果供诊断;</li>
 *     <li>answerable=true 但 claimFail=true 时: 判定可作答(证据充分), 但生成回答未通过验证, 响应无 answer。</li>
 * </ul>
 */
@Data
public class EvidenceEvaluateRespVO {

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
    private List<EvidenceItemVO> evidence;

    /** 冲突列表(evidenceIndexA/B 为 evidence 列表位置索引) */
    private List<ConflictVO> conflicts;

    /** 生成的回答(全部断言通过验证时非空) */
    private String answer;

    /** 逐句断言验证结果 */
    private List<ClaimVO> claims;

    /** 是否验证失败(生成失败/重试耗尽 → true, 此时 answer=null) */
    private Boolean claimFail;

    /** 评估耗时(ms, 不含落库) */
    private Integer elapsedMs;

    /** 回显本次使用的上下文(供落库快照/前端展示) */
    private List<ChatTurnDTO> history;

    @Data
    public static class EvidenceItemVO {

        /** 片段编号 */
        private Long chunkId;

        /** 片段内容 */
        private String content;

        /** 来源文档名 */
        private String documentName;

        /** 版本号: V1/V2/... */
        private String versionNo;

        /** 归一化得分(0~1, 批次内 min-max) */
        private Double score;

        /** 命中通道: ["bm25"] / ["vector"] / ["bm25","vector"] */
        private List<String> channels;
    }

    @Data
    public static class ConflictVO {

        /** 证据 A 在 evidence 列表中的位置索引 */
        private Integer evidenceIndexA;

        /** 证据 B 在 evidence 列表中的位置索引 */
        private Integer evidenceIndexB;

        /** 矛盾原因说明 */
        private String reason;
    }

    @Data
    public static class ClaimVO {

        /** 断言句子原文 */
        private String text;

        /** 判定: SUPPORTED / UNSUPPORTED */
        private String verdict;

        /** 支撑证据在 evidence 列表中的位置索引(0 起; -1 = 无支撑) */
        private Integer evidenceIndex;
    }

}
