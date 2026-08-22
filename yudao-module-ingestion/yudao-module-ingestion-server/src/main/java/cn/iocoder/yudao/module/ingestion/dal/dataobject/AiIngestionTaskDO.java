package cn.iocoder.yudao.module.ingestion.dal.dataobject;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 入库阶段级 Trace(Knowledge Ops: 每阶段状态/耗时/输入输出摘要/错误)
 */
@TableName("ai_ingestion_task")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiIngestionTaskDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 入库任务编号 */
    private Long jobId;

    /** 阶段: FETCH/VALIDATE/PARSE/STRUCTURE/METADATA/CHUNK/EMBED/PERSIST/REVIEW_PREPARE/DONE */
    private String stageCode;

    /** 阶段顺序 */
    private Integer stageOrder;

    /** 处理器 */
    private String handler;

    /** 处理器版本 */
    private String handlerVersion;

    /** 尝试次数 */
    private Integer attempt;

    /** 状态: RUNNING/SUCCEEDED/FAILED */
    private String status;

    /** 输入摘要(JSON) */
    private String inputSummaryJson;

    /** 输出摘要(JSON) */
    private String outputSummaryJson;

    /** 指标(JSON: 耗时/数量/维度) */
    private String metricsJson;

    /** 负载引用 */
    private String payloadRef;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

}
