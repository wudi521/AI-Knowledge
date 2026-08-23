package cn.iocoder.yudao.module.chat.controller.admin.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - AI 对话发送 Response VO")
@Data
public class ChatSendRespVO {

    @Schema(description = "会话编号(新建会话时为新建会话 id)")
    private Long conversationId;

    @Schema(description = "本次返回的最终消息编号")
    private Long messageId;

    @Schema(description = "本次会话使用的知识库编号")
    private Long kbId;

    @Schema(description = "本次会话使用的知识领域")
    private String domainCode;

    @Schema(description = "路由结果")
    private String route;

    @Schema(description = "意图结果")
    private String intent;

    @Schema(description = "是否为降级结果")
    private Boolean degraded;

    @Schema(description = "AI 回复内容(answerable=true 时有值)")
    private String reply;

    @Schema(description = "是否可作答")
    private Boolean answerable;

    @Schema(description = "证据充分度融合置信度(0~1)")
    private Double confidence;

    @Schema(description = "引用证据 chunkId 列表(claims 中 SUPPORTED 断言引用的证据, 保序去重)")
    private List<Long> citations;

    /** 证据摘要(专利来源卡片: chunkId/文档名/元数据/引用原文) */
    private List<cn.iocoder.yudao.module.chat.service.chat.ChatSendResult.EvidenceSummary> evidenceList;

    @Schema(description = "证据评估链路追踪号(ev- 前缀)")
    private String traceId;

    @Schema(description = "是否需转人工(answerable=false / 评估服务不可用 / Claim 验证失败)")
    private Boolean transferRequired;

    @Schema(description = "转人工原因(transferRequired=true 时填充)")
    private String transferReason;

    @Schema(description = "会话摘要(转人工时填充; 已由 TransferHandler 落库到 ai_conversation.summary 与 SYSTEM 消息)")
    private String summary;

}
