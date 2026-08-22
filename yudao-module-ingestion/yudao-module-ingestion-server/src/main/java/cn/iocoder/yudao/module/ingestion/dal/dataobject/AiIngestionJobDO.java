package cn.iocoder.yudao.module.ingestion.dal.dataobject;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 入库持久化任务(文档入库状态机: 消费端幂等/断点续跑/失败重试)
 */
@TableName("ai_ingestion_job")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiIngestionJobDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 文档编号 */
    private Long documentId;

    /** 知识库编号(Knowledge Ops 链路) */
    private Long kbId;

    /** 领域代码(GENERAL/PATENT) */
    private String domainCode;

    /** 版本编号 */
    private Long versionId;

    /** 任务类型: INGEST */
    private String jobType;

    /** 阶段: FETCH/VALIDATE/PARSE/STRUCTURE/CHUNK/EMBED/PERSIST/REVIEW_PREPARE/DONE/FAILED */
    private String stage;

    /** 状态: PENDING/RUNNING/SUCCEEDED/FAILED/RETRYING */
    private String status;

    /** 幂等键(默认 documentId) */
    private String idempotencyKey;

    /** 载荷哈希 */
    private String payloadHash;

    /** 总量(如 chunk 数) */
    private Integer total;

    /** 进度 */
    private Integer progress;

    /** 重试次数 */
    private Integer retryCount;

    /** 最大重试 */
    private Integer maxRetry;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    /** 租约持有者 */
    private String leaseOwner;

    /** 租约过期时间 */
    private LocalDateTime leaseExpireAt;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMessage;

    /** 链路追踪号 */
    private String traceId;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /** 乐观锁版本 */
    private Integer optimisticVersion;

}
