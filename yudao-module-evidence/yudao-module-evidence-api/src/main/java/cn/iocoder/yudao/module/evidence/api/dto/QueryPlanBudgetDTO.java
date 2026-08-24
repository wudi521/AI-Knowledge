package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * Composite Query Plan 预算。Chat 可显式下发；管理端 /evaluate 不下发时使用证据侧安全默认值。
 */
@Data
public class QueryPlanBudgetDTO {

    /** 计划最大步骤数(默认 5，服务端最大接受 8) */
    private Integer maxSteps;

    /** 计划最大实体数(默认 10，服务端最大接受 50；超限要求缩小范围) */
    private Integer maxEntities;

    /** 模型调用预算(默认 2，服务端最大接受 4；Structured/ExactText 为 0) */
    private Integer maxModelCalls;

    /** 计划整体 deadline ms(默认 20s，服务端最大接受 60s) */
    private Long deadlineMs;

}
