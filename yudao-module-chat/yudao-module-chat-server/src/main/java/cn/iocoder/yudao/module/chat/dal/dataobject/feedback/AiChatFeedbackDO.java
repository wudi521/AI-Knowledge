package cn.iocoder.yudao.module.chat.dal.dataobject.feedback;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * AI 回答反馈 DO(ai_chat_feedback, 每条 AI 消息唯一当前反馈)
 * <p>
 * rating: HELPFUL 有用 / NOT_HELPFUL 无用(见 {@link cn.iocoder.yudao.module.chat.enums.feedback.FeedbackRatingEnum});
 * 自动关联 message→Query Trace→Evidence 上下文, 供 Bad Case / FAQ Candidate 复现;
 * 点踩反馈落库后异步生成评测用例, 编号回填 evalCaseId(反馈→考题闭环)。
 */
@TableName("ai_chat_feedback")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatFeedbackDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被反馈的 AI 消息编号(ai_message.id, UNIQUE) */
    private Long messageId;

    /** 会话编号 */
    private Long conversationId;

    /** 统一主追踪号(q- 前缀) */
    private String queryTraceId;

    /** 证据评估链路追踪号(ev- 前缀) */
    private String traceId;

    /** 反馈用户编号 */
    private Long userId;

    /** 知识库编号 */
    private Long kbId;

    /** 知识领域编码 */
    private String domainCode;

    /** 评价: HELPFUL 有用 / NOT_HELPFUL 无用 */
    private String rating;

    /** 无用原因(见 FeedbackReasonEnum) */
    private String reasonCode;

    /** 备注(用户输入) */
    private String comment;

    /** 回答路由 */
    private String route;

    /** 意图 */
    private String intent;

    /** 证据充分度融合置信度(0~1) */
    private BigDecimal confidence;

    /** 回答耗时(ms) */
    private Long latencyMs;

    /** 模型编号 */
    private Long modelId;

    /** 提示词版本 */
    private String promptVersion;

    /** 主证据文档编号 */
    private Long primaryDocumentId;

    /** 证据快照(JSON, 反馈时点), 供 Bad Case 复现 */
    private String evidenceSnapshot;

    /** 生成的评测用例编号(点踩闭环回填, 未生成/失败为空) */
    private Long evalCaseId;

}
