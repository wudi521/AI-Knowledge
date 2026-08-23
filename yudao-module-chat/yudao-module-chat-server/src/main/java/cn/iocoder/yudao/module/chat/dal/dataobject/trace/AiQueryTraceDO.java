package cn.iocoder.yudao.module.chat.dal.dataobject.trace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI 查询 Trace 主表(ai_query_trace)
 * <p>
 * 每一个用户问题一个主 traceId(q- 前缀), 全链路阶段挂在 {@link AiQueryTraceStageDO}。
 */
@TableName("ai_query_trace")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiQueryTraceDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 统一主追踪号(q- 前缀) */
    private String traceId;

    /** 会话编号 */
    private Long conversationId;

    /** 消息编号 */
    private Long messageId;

    /** 用户问题 */
    private String query;

    /** 检索路由 */
    private String route;

    /** 知识库编号 */
    private Long kbId;

    /** 知识领域编码 */
    private String domainCode;

    /** 整体耗时(ms) */
    private Long totalMs;

    /** 状态: SUCCEEDED / FAILED / DEGRADED / TIMEOUT */
    private String status;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

}
