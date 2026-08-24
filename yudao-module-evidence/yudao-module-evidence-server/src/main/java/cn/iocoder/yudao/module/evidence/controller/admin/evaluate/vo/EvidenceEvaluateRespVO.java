package cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
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
    private List<SlotValueVO> extractedSlots;

    /** 槽位检测: 缺失的必填槽位列表(value 恒为 null) */
    private List<SlotValueVO> missingSlots;

    /** 槽位检测: 反问句(缺必填槽位时填充; 如 "请补充以下信息:品牌型号、故障性质、购机时间") */
    private String clarifyQuestion;

    /** 语义分析详情(意图/实体/改写/子问题; 透传检索结果, 供前端检索诊断) */
    private RetrievalSearchRespDTO.RetrievalAnalysisDTO analysis;

    /** 检索路由(Query Planner 权威产出: RULE/EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN) */
    private String route;

    /** 意图(如 STRUCTURED_AGGREGATE; 聚合等确定性路径不经过 retrieval analysis) */
    private String intent;

    /** 通道召回统计(BM25/向量/融合; 供前端检索诊断) */
    private RetrievalSearchRespDTO.RetrievalChannelStatDTO channels;

    /** 结构化查询结果回流(CQ-02/03; 结构化路径携带保序实体 id, 供 chat 侧形成 ResultSetSnapshot) */
    private cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO structuredResult;

    /** CQ-38: 执行模式(STRUCTURED / PER_ENTITY_SEMANTIC / CROSS_ENTITY_SEMANTIC; 语义执行路径) */
    private String executionMode;

    /** CQ-38: 结构化失败/澄清原因码(AMBIGUOUS_SCOPE/UNSUPPORTED_FIELD/MISSING_METRIC/...; 供上层可解释/可引导) */
    private String reasonCode;

    @Data
    public static class SlotValueVO {

        /** 槽位编码 */
        private String code;

        /** 槽位名 */
        private String name;

        /** 抽取到的原文(缺失项恒为 null) */
        private String value;

    }

    @Data
    public static class EvidenceItemVO {

        /** 证据编号(去重后 chunkId) */
        private Long evidenceId;

        /** 片段编号 */
        private Long chunkId;

        /** 片段内容 */
        private String content;

        /** 片段元数据(JSON; 专利来源卡片) */
        private String chunkMetadata;

        /** 来源文档名 */
        private String documentName;

        /** 版本号: V1/V2/... */
        private String versionNo;

        /** 版本编号 */
        private Long versionId;

        /** 来源文档编号 */
        private Long documentId;

        /** 知识库编号 */
        private Long kbId;

        /** 知识领域编码 */
        private String domainCode;

        /** 片段类型 */
        private String sectionType;

        /** 片段小节标题 */
        private String sectionTitle;

        /** 权利要求编号 */
        private String claimNo;

        /** 起始页码 */
        private Integer pageStart;

        /** 结束页码 */
        private Integer pageEnd;

        /** 申请号 */
        private String applicationNo;

        /** 公布号 */
        private String publicationNo;

        /** 归一化得分(0~1, 批次内 min-max) */
        private Double score;

        /** 证据类型: CHUNK / STRUCTURED_RESULT */
        private String evidenceType;

        /** 聚合指标(STRUCTURED_RESULT) */
        private String metric;

        /** 聚合结果值(STRUCTURED_RESULT) */
        private Integer aggregateValue;

        /** 聚合过滤条件(STRUCTURED_RESULT) */
        private String filters;

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
