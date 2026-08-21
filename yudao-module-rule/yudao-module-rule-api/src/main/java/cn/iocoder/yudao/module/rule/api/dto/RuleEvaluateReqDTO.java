package cn.iocoder.yudao.module.rule.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 规则评估请求 DTO
 */
@Data
public class RuleEvaluateReqDTO {

    /** 业务键(如 warranty-condition/delivery-condition) */
    private String ruleKey;

    /** 租户编号(可空, 服务端 TenantContextHolder 兜底) */
    private Long tenantId;

    /** 事实(Map; 规则条件用 $f["key"] 读取, 如 {query, region, ...}) */
    private Map<String, Object> facts;

}
