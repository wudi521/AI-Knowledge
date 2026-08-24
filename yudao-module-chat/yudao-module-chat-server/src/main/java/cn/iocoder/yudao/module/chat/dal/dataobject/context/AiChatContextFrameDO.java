package cn.iocoder.yudao.module.chat.dal.dataobject.context;

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

/**
 * 多轮查询上下文帧 DO(ai_chat_context_frame)
 * <p>
 * 每轮 query 成功后 push 一帧, Resolver 从最近到远匹配(ResultSet reference / Ordinal / Cardinality /
 * Metric-Field 继承)。不维护单一全局 mutable lastContext, 避免十几轮后串帧。
 */
@TableName("ai_chat_context_frame")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatContextFrameDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话编号 */
    private Long conversationId;

    /** 帧序号(递增) */
    private Integer seq;

    /** 查询编号(q- 前缀) */
    private String queryId;

    /** 实体类型 */
    private String entityType;

    /** 关联结果集编号 */
    private String resultSetId;

    /** 指标编码(如 CLAIM_COUNT) */
    private String metricCode;

    /** 字段编码(如 PUBLICATION_NO) */
    private String fieldCode;

    /** 聚合运算(COUNT/SUM/AVG/MIN/MAX/NONE) */
    private String operation;

    /** 范围类型(CURRENT_KB/PREVIOUS_RESULT_SET/EXPLICIT_ENTITY/DOCUMENT_SET) */
    private String scopeType;

    /** 查询类型(EXACT_LOOKUP/LIST/GROUP/AGGREGATE/SORT/TOP_N/SCOPED_RAG) */
    private String queryType;

    /** 执行模式(STRUCTURED/PER_ENTITY_SEMANTIC/CROSS_ENTITY_SEMANTIC/HYBRID) */
    private String executionMode;

    /** 产生该帧的查询文本(摘要) */
    private String queryText;

}
