package cn.iocoder.yudao.module.evidence.api.dto;

import lombok.Data;

/**
 * Composite Query Plan 预算(CQ-02/38): 由对话层下发, 约束单次查询的执行上限。
 * <p>
 * 对话层配置 yudao.chat.plan-max-steps / plan-max-entities / plan-max-model-calls / plan-deadline-ms,
 * 经评估 RPC 传入证据侧 Composite Query Executor。null 字段在证据侧用默认值兜底。
 */
@Data
public class QueryPlanBudgetDTO {

    /** 计划最大步骤数(默认 5) */
    private Integer maxSteps;

    /** 计划最大实体数(默认 100; 逐实体语义执行上限) */
    private Integer maxEntities;

    /** 计划最大模型调用数(默认 12; 语义执行生成计数) */
    private Integer maxModelCalls;

    /** 计划整体 deadline ms(默认 60s; 超时中止返回降级) */
    private Long deadlineMs;

}
