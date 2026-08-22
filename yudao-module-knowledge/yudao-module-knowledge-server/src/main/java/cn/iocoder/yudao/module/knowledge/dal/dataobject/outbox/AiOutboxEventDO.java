package cn.iocoder.yudao.module.knowledge.dal.dataobject.outbox;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 事务性 Outbox 事件(与业务变更同事务提交, 由 Publisher 可靠发送 Kafka)
 */
@TableName("ai_outbox_event")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiOutboxEventDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 聚合类型: DOCUMENT */
    private String aggregateType;

    /** 聚合编号(如文档编号) */
    private Long aggregateId;

    /** 事件类型: DOCUMENT_CREATED */
    private String eventType;

    /** 事件载荷(JSON) */
    private String payload;

    /** 幂等键 */
    private String idempotencyKey;

    /** 状态: PENDING/SENT/FAILED */
    private String status;

    /** 发送重试次数 */
    private Integer retryCount;

    /** 发送成功时间 */
    private LocalDateTime sentAt;

}
