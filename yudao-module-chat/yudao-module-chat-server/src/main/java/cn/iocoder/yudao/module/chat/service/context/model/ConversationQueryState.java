package cn.iocoder.yudao.module.chat.service.context.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ConversationQueryState(会话轻量查询状态, CQ-01)
 * <p>
 * 服务端 Source of Truth; 只存引用/计数, 不存大 ID 集(大结果集在 ai_chat_result_set)。
 * 前端不得回传 documentIds 覆盖本状态。
 */
@Data
@Builder
public class ConversationQueryState {

    private String lastResultSetId;
    private String entityType;
    private Integer entityCount;
    private String lastMetric;
    private String lastField;
    private String lastOperation;
    private String lastQueryId;
    private LocalDateTime lastUpdatedAt;

}
