package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Data;

import java.util.List;

/**
 * 多轮上下文解析结果轻量载体(CQ-04~10): 由 chat 侧 ReferenceResolver 产出,
 * 经 EvidenceEvaluateReqDTO.contextResolutionJson 传入; 供结构化引擎消解范围/字段。
 */
@Data
public class StructuredContextHint {

    /** 已消解的实体 id(上一轮结果集应用子集后; 空 = 未消解) */
    private List<Long> explicitEntityIds;

    /** 已继承/解析的字段编码(如 PUBLICATION_NO; 可空) */
    private String fieldCode;

    /** 已继承/解析的指标编码(如 CLAIM_COUNT; 可空) */
    private String metricCode;

    /** 范围类型(PREVIOUS_RESULT_SET/EXPLICIT_ENTITY/CURRENT_KB; 可空) */
    private String scopeType;

}
