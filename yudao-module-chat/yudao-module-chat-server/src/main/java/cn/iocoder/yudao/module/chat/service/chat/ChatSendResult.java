package cn.iocoder.yudao.module.chat.service.chat;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 对话发送结果(ChatPipeline 编排产物)
 * <p>
 * 两种形态:
 * <ul>
 *     <li><b>可作答</b>: {@code answerable=true}, {@code reply} 为 AI 回答, 已落库 AI 消息;</li>
 *     <li><b>转人工</b>: {@code transferRequired=true}, {@code reply=null}, 携带 {@code transferReason} 与
 *     {@code summary} —— 由 {@code ChatPipeline} 内 {@code TransferHandler.handleTransfer} 完成
 *     状态迁移(ACTIVE→TRANSFERRED) + SYSTEM 交接摘要落库后返回。</li>
 * </ul>
 */
@Data
@Builder
public class ChatSendResult {

    /** 会话编号(新建会话时为新建会话 id) */
    private Long conversationId;

    /** 本次返回的最终消息编号 */
    private Long messageId;

    /** 本次会话使用的知识库编号 */
    private Long kbId;

    /** 本次会话使用的知识领域 */
    private String domainCode;

    /** 路由结果(评估响应未提供时为空) */
    private String route;

    /** 意图结果(评估响应未提供时为空) */
    private String intent;

    /** 是否为降级结果 */
    private Boolean degraded;

    /** AI 回答内容(answerable=true 时有值) */
    private String answer;

    /** 是否可作答 */
    private Boolean answerable;

    /** 证据充分度融合置信度(0~1) */
    private Double confidence;

    /** 引用证据 chunkId 列表(claims 中 SUPPORTED 断言引用的证据, 保序去重) */
    private List<Long> citations;

    /** 证据摘要列表(统一 Evidence DTO: PATENT 卡片展示 名称/申请号/公布号/Claim/Section/Page/原文) */
    private List<EvidenceSummary> evidence;

    /** 证据评估链路追踪号(ev- 前缀) */
    private String traceId;

    /** 统一主追踪号(q- 前缀; 流式 done 事件透传, 反馈/校验关联 Query Trace 用) */
    private String queryTraceId;

    /** 本次请求整体耗时(ms) */
    private Integer latencyMs;

    /** 是否需转人工(answerable=false / 评估服务不可用 / Claim 验证失败) */
    private Boolean transferRequired;

    /** 转人工原因(transferRequired=true 时填充) */
    private String transferReason;

    /** 会话摘要(转人工时填充; 已由 TransferHandler 落库到 ai_conversation.summary 与 SYSTEM 消息) */
    private String summary;

    /**
     * 统一 Evidence DTO(商用化契约)
     * <p>
     * 面向业务展示, 禁止携带 Vector ID / Milvus PK / 原始 BM25 分 / 内部 JSON / Embedding 信息。
     * score 为批次内 min-max 归一化融合置信度(0~1), 非原始检索分。
     */
    @lombok.Data
    @lombok.Builder
    public static class EvidenceSummary {
        /** 证据编号(去重后 chunkId) */
        private Long evidenceId;
        /** 片段编号 */
        private Long chunkId;
        /** 来源文档编号 */
        private Long documentId;
        /** 来源文档名 */
        private String documentName;
        /** 版本编号(片段所属版本; 历史快照回看用) */
        private Long versionId;
        /** 版本号: V1/V2/... */
        private String versionNo;
        /** 知识库编号 */
        private Long kbId;
        /** 知识领域编码(如 PATENT) */
        private String domainCode;
        /** 片段类型(专利: 权利要求书/说明书/著录信息 等) */
        private String sectionType;
        /** 片段小节标题(如 权利要求 1 / 发明内容) */
        private String sectionTitle;
        /** 权利要求编号(权利要求片段) */
        private String claimNo;
        /** 起始页码 */
        private Integer pageStart;
        /** 结束页码 */
        private Integer pageEnd;
        /** 申请号 */
        private String applicationNo;
        /** 公布号 */
        private String publicationNo;
        /** 引用原文(截断) */
        private String content;
        /** 归一化得分(0~1) */
        private Double score;
        /** 证据类型: CHUNK(默认) / STRUCTURED_AGGREGATE */
        private String evidenceType;
        /** 聚合指标(STRUCTURED_AGGREGATE: DOCUMENT_COUNT/PATENT_COUNT/KNOWLEDGE_ENTRY_COUNT) */
        private String metric;
        /** 聚合结果值(STRUCTURED_AGGREGATE) */
        private Integer aggregateValue;
        /** 聚合过滤条件(STRUCTURED_AGGREGATE) */
        private String filters;
    }

}
