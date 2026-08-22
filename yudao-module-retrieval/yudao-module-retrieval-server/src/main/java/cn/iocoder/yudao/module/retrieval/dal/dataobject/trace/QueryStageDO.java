package cn.iocoder.yudao.module.retrieval.dal.dataobject.trace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 查询阶段级 Trace(Knowledge Ops Query Trace: 每阶段状态/摘要/耗时)
 */
@TableName("ai_query_stage")
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryStageDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 查询链路追踪号 */
    private String traceId;

    /** 阶段: QUERY_ANALYSIS/REWRITE/SCOPE/BM25/VECTOR/FUSION/RERANK/EVIDENCE/GENERATE/VERIFY */
    private String stageCode;

    /** 阶段顺序 */
    private Integer stageOrder;

    /** 处理器 */
    private String handler;

    /** 处理器版本 */
    private String handlerVersion;

    /** 状态: RUNNING/SUCCEEDED/FAILED/SKIPPED */
    private String status;

    /** 输入摘要(JSON) */
    private String inputSummaryJson;

    /** 输出摘要(JSON) */
    private String outputSummaryJson;

    /** 指标(JSON) */
    private String metricsJson;

    /** 负载引用(候选 chunk 列表等) */
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
