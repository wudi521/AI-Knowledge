package cn.iocoder.yudao.module.chat.dal.dataobject.trace;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 查询 Trace 阶段表(ai_query_trace_stage)
 * <p>
 * 全链路阶段: ANALYZE/ROUTE/REWRITE/SCOPE_FILTER/DOC_LOOKUP/CLAIM_LOOKUP/BM25/VECTOR/FUSION/RERANK/
 * EVIDENCE/GENERATE/VERIFY/REPAIR。仅记录阶段/状态/耗时/脱敏摘要, 禁止记录完整敏感 Prompt / Token / 密码。
 */
@TableName("ai_query_trace_stage")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiQueryTraceStageDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 统一主追踪号 */
    private String traceId;

    /** 阶段顺序 */
    private Integer seq;

    /** 阶段编码 */
    private String stage;

    /** 状态: SUCCEEDED / FAILED / SKIPPED */
    private String status;

    /** 耗时(ms) */
    private Long elapsedMs;

    /** 是否跳过 */
    private Boolean skipped;

    /** 错误码 */
    private String errorCode;

    /** 错误信息(脱敏) */
    private String errorMessage;

    /** 模型调用编号 */
    private String modelCallId;

    /** 输入摘要(不含敏感内容) */
    private String inputSummary;

    /** 输出摘要(不含敏感内容) */
    private String outputSummary;

}
