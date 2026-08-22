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

    /** AI 回复内容(answerable=true 时有值) */
    private String reply;

    /** 是否可作答 */
    private Boolean answerable;

    /** 证据充分度融合置信度(0~1) */
    private Double confidence;

    /** 引用证据 chunkId 列表(claims 中 SUPPORTED 断言引用的证据, 保序去重) */
    private List<Long> citations;

    /** 证据摘要列表(专利来源卡片: chunkId/文档名/元数据JSON/引用原文) */
    private List<EvidenceSummary> evidenceList;

    /** 证据评估链路追踪号(ev- 前缀) */
    private String traceId;

    /** 是否需转人工(answerable=false / 评估服务不可用 / Claim 验证失败) */
    private Boolean transferRequired;

    /** 转人工原因(transferRequired=true 时填充) */
    private String transferReason;

    /** 会话摘要(转人工时填充; 已由 TransferHandler 落库到 ai_conversation.summary 与 SYSTEM 消息) */
    private String summary;

    /** 证据摘要(来源卡片数据) */
    @lombok.Data
    @lombok.Builder
    public static class EvidenceSummary {
        private Long chunkId;
        private String documentName;
        private String versionNo;
        private String chunkMetadata; // 专利: applicationNo/publicationNo/sectionType/claimNo/pageStart
        private String content;       // 引用原文(截断)
    }

}
