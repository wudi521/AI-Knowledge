package cn.iocoder.yudao.module.evidence.service.agent.validation;

/** 单个领域验证规则的结果；PASS/ABSTAIN 不终止通用独立 Evaluator。 */
public record GoalValidationRuleResult(Decision decision, String reason, String message) {

    public enum Decision {
        PASS,
        ABSTAIN,
        INSUFFICIENT,
        NEED_MORE_INFO,
        FAILED
    }

    public static GoalValidationRuleResult pass(String reason) {
        return new GoalValidationRuleResult(Decision.PASS, reason, null);
    }

    public static GoalValidationRuleResult abstain() {
        return new GoalValidationRuleResult(Decision.ABSTAIN, null, null);
    }

    public static GoalValidationRuleResult insufficient(String reason) {
        return new GoalValidationRuleResult(Decision.INSUFFICIENT, reason, null);
    }

    public static GoalValidationRuleResult needMoreInfo(String reason, String message) {
        return new GoalValidationRuleResult(Decision.NEED_MORE_INFO, reason, message);
    }

    public static GoalValidationRuleResult failed(String reason) {
        return new GoalValidationRuleResult(Decision.FAILED, reason, null);
    }
}
