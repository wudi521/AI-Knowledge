package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 权威结构化聚合请求。
 *
 * <p>只传受控 metricCode，不接受 SQL/列名/表达式。具体 metric 到存储字段的映射由服务端白名单决定。</p>
 */
@Data
public class StructuredAggregateReqDTO {
    private Long kbId;
    private String domainCode;
    private String metricCode;
    private Boolean publishedOnly;
    private List<Long> resolvedEntityIds;
}
