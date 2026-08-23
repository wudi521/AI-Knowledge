package cn.iocoder.yudao.module.chat.controller.admin.chat.vo;

import cn.iocoder.yudao.module.chat.service.chat.ChatSendResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SSE 流式对话事件(ChatStreamEvent)
 * <p>
 * 统一事件类型 {@link #type}:
 * <ul>
 *     <li>conversation —— 连接建立且 Query Context 创建完成, 尽早返回;</li>
 *     <li>stage —— 执行阶段(与 Query Trace 同源, 见 {@code ai_query_trace_stage});</li>
 *     <li>evidence —— 最终证据集合稳定后推送一次;</li>
 *     <li>delta —— 仅用于最终 Answer 内容增量;</li>
 *     <li>verification —— 回答校验结果;</li>
 *     <li>done —— 最终权威状态(前端不可仅依赖拼接 delta 作为持久化结果);</li>
 *     <li>error —— 业务/系统错误。</li>
 * </ul>
 * 禁止携带隐藏 CoT / 完整 Prompt / Token / Authorization Header / 内部异常堆栈。
 */
@Data
@Builder
public class ChatStreamEvent {

    public static final String TYPE_CONVERSATION = "conversation";
    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_EVIDENCE = "evidence";
    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_VERIFICATION = "verification";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";

    @Schema(description = "事件类型: conversation/stage/evidence/delta/verification/done/error", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    // ==================== conversation ====================
    @Schema(description = "会话编号")
    private Long conversationId;

    @Schema(description = "问题编号(本实现与 traceId 同源)")
    private String queryId;

    @Schema(description = "统一主追踪号(q- 前缀)")
    private String traceId;

    @Schema(description = "知识库编号")
    private Long kbId;

    @Schema(description = "知识领域编码")
    private String domainCode;

    // ==================== stage ====================
    @Schema(description = "阶段编码(ANALYZE/ROUTE/SCOPE_FILTER/BM25/VECTOR/FUSION/RERANK/EVIDENCE/GENERATE/VERIFY/REPAIR 等)")
    private String stage;

    @Schema(description = "阶段状态: RUNNING/DONE/SKIPPED/FAILED/DEGRADED")
    private String status;

    @Schema(description = "阶段中文标签")
    private String label;

    @Schema(description = "阶段耗时(ms)")
    private Long elapsedMs;

    @Schema(description = "输入摘要(不含敏感内容)")
    private String inputSummary;

    @Schema(description = "输出摘要(不含敏感内容)")
    private String outputSummary;

    @Schema(description = "错误码(阶段失败时)")
    private String errorCode;

    @Schema(description = "模型调用编号")
    private String modelCallId;

    // ==================== evidence ====================
    @Schema(description = "证据数量")
    private Integer count;

    @Schema(description = "证据列表(统一 Evidence DTO)")
    private List<ChatSendResult.EvidenceSummary> items;

    // ==================== delta ====================
    @Schema(description = "Answer 内容增量")
    private String content;

    // ==================== verification ====================
    @Schema(description = "校验结果: PASSED/FAILED")
    private String verifyStatus;

    @Schema(description = "修复次数")
    private Integer repairCount;

    // ==================== done / error 公共 ====================
    @Schema(description = "AI 消息编号(done)")
    private Long messageId;

    @Schema(description = "路由(done)")
    private String route;

    @Schema(description = "是否可作答(done)")
    private Boolean answerable;

    @Schema(description = "最终权威回答(done, 可修正流式草稿)")
    private String answer;

    @Schema(description = "引用证据 chunkId 列表(done)")
    private List<Long> citations;

    @Schema(description = "证据列表(done)")
    private List<ChatSendResult.EvidenceSummary> evidence;

    @Schema(description = "置信度(done)")
    private Double confidence;

    @Schema(description = "整体耗时 ms(done)")
    private Integer latencyMs;

    @Schema(description = "是否降级(done)")
    private Boolean degraded;

    @Schema(description = "是否需转人工(done)")
    private Boolean transferRequired;

    @Schema(description = "转人工原因(done)")
    private String transferReason;

    // ==================== error ====================
    @Schema(description = "错误码(error)")
    private String code;

    @Schema(description = "错误信息(error, 用户可读)")
    private String message;

    @Schema(description = "是否可重试(error)")
    private Boolean retryable;

}
