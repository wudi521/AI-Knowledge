package cn.iocoder.yudao.module.chat.service.context.model;

import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import lombok.Builder;
import lombok.Data;

/**
 * ContextFrame(多轮查询上下文帧, CQ-34)
 * <p>
 * 每轮 query 成功后 push 一帧; Resolver 从最近到远匹配, 不维护单一 mutable lastContext。
 */
@Data
@Builder
public class ContextFrame {

    private Long conversationId;
    private Integer seq;
    private String queryId;
    private String entityType;
    private String resultSetId;
    private String metricCode;
    private String fieldCode;
    private String operation;
    private String scopeType;
    private String queryType;
    private String executionMode;
    private String queryText;

    public static ContextFrame fromDO(AiChatContextFrameDO row) {
        if (row == null) {
            return null;
        }
        return ContextFrame.builder()
                .conversationId(row.getConversationId())
                .seq(row.getSeq())
                .queryId(row.getQueryId())
                .entityType(row.getEntityType())
                .resultSetId(row.getResultSetId())
                .metricCode(row.getMetricCode())
                .fieldCode(row.getFieldCode())
                .operation(row.getOperation())
                .scopeType(row.getScopeType())
                .queryType(row.getQueryType())
                .executionMode(row.getExecutionMode())
                .queryText(row.getQueryText())
                .build();
    }

    public AiChatContextFrameDO toDO() {
        AiChatContextFrameDO row = new AiChatContextFrameDO();
        row.setConversationId(conversationId);
        row.setSeq(seq);
        row.setQueryId(queryId);
        row.setEntityType(entityType);
        row.setResultSetId(resultSetId);
        row.setMetricCode(metricCode);
        row.setFieldCode(fieldCode);
        row.setOperation(operation);
        row.setScopeType(scopeType);
        row.setQueryType(queryType);
        row.setExecutionMode(executionMode);
        row.setQueryText(queryText);
        return row;
    }

}
