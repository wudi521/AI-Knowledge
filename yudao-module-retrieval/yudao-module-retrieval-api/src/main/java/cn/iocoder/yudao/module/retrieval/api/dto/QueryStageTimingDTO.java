package cn.iocoder.yudao.module.retrieval.api.dto;

import lombok.Data;

/**
 * 查询阶段耗时/状态 Trace DTO(Query Trace 阶段记录)
 * <p>
 * 用于跨 chat/retrieval/evidence 汇聚统一主 traceId 下的全链路阶段(ANALYZE/ROUTE/BM25/VECTOR/
 * FUSION/RERANK/EVIDENCE/GENERATE/VERIFY/REPAIR)。禁止记录完整敏感 Prompt / Access Token /
 * 密码 / Authorization Header。
 */
@Data
public class QueryStageTimingDTO {

    /** 阶段编码: ANALYZE/ROUTE/REWRITE/SCOPE_FILTER/DOC_LOOKUP/CLAIM_LOOKUP/BM25/VECTOR/FUSION/RERANK/EVIDENCE/GENERATE/VERIFY/REPAIR */
    private String stage;

    /** 阶段顺序(递增) */
    private Integer seq;

    /** 状态: SUCCEEDED / FAILED / SKIPPED */
    private String status;

    /** 阶段耗时(ms; SKIPPED 时为 0) */
    private Long elapsedMs;

    /** 是否跳过(因路由未执行该阶段) */
    private Boolean skipped;

    /** 错误码(失败时填充) */
    private String errorCode;

    /** 错误信息(失败时填充, 脱敏) */
    private String errorMessage;

    /** 模型调用编号(模型阶段填充; 关联模型日志) */
    private String modelCallId;

    /** 输入摘要(不含敏感内容) */
    private String inputSummary;

    /** 输出摘要(不含敏感内容) */
    private String outputSummary;

}
