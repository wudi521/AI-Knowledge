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
 *     {@code summary} 草稿 —— 实际状态迁移 + SYSTEM 消息由 Task 4 TransferHandler 完成, T3 仅输出决策。</li>
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

    /** 证据评估链路追踪号(ev- 前缀) */
    private String traceId;

    /** 是否需转人工(answerable=false / 评估服务不可用 / Claim 验证失败) */
    private Boolean transferRequired;

    /** 转人工原因(transferRequired=true 时填充) */
    private String transferReason;

    /** 会话摘要草稿(转人工时填充, Task 4 TransferHandler 落库前可再提炼) */
    private String summary;

}
