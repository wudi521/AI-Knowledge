package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

/**
 * 领域 Goal 验证规则 SPI。
 *
 * <p>规则只能增加证明要求或要求补信息，不能绕过平台权限、Result Integrity、Provenance Integrity，
 * 也不能直接生成答案。PASS 之后仍会进入独立通用 Goal Evaluator。</p>
 */
public interface GoalValidationRulePlugin extends DomainPipelinePlugin {

    GoalValidationRuleResult validate(GoalValidationContext context);
}
