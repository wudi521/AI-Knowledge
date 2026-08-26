package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 Goal Validation Rule Pipeline。
 *
 * <p>Pipeline 只负责编排当前领域的确定性证明规则；PASS/ABSTAIN 继续，
 * INSUFFICIENT/NEED_MORE_INFO/FAILED fail-closed。最终通用充分性判断仍由独立 Goal Evaluator 完成。</p>
 */
@Component
public class GoalValidationRulePipeline {

    private final DomainPluginResolver<GoalValidationRulePlugin> resolver;

    public GoalValidationRulePipeline(List<GoalValidationRulePlugin> rules) {
        this.resolver = new DomainPluginResolver<>(rules);
    }

    public Result validate(GoalValidationContext context) {
        List<String> executed = new ArrayList<>();
        for (GoalValidationRulePlugin plugin : resolver.resolve(pluginContext(context))) {
            executed.add(plugin.pluginId());
            GoalValidationRuleResult ruleResult;
            try {
                ruleResult = plugin.validate(context);
            } catch (Exception e) {
                ruleResult = GoalValidationRuleResult.failed(
                        "validation plugin failed: " + e.getClass().getSimpleName());
            }
            if (ruleResult == null
                    || ruleResult.decision() == GoalValidationRuleResult.Decision.PASS
                    || ruleResult.decision() == GoalValidationRuleResult.Decision.ABSTAIN) {
                continue;
            }
            return new Result(plugin.pluginId(), ruleResult, List.copyOf(executed));
        }
        return new Result(null, GoalValidationRuleResult.pass("all applicable domain rules passed"),
                List.copyOf(executed));
    }

    private DomainPluginContext pluginContext(GoalValidationContext context) {
        CapabilityInvocationContext invocation = context == null ? null : context.invocationContext();
        if (invocation == null) return DomainPluginContext.of("GENERAL");
        return new DomainPluginContext(invocation.tenantId(), invocation.kbId(), invocation.domainCode(),
                Set.of("GOAL_VALIDATION"), Map.of());
    }

    public record Result(String terminalPluginId,
                         GoalValidationRuleResult ruleResult,
                         List<String> executedPluginIds) {
        public Result {
            executedPluginIds = executedPluginIds == null ? List.of() : List.copyOf(executedPluginIds);
        }

        public boolean allowsFallbackEvaluator() {
            return ruleResult == null
                    || ruleResult.decision() == GoalValidationRuleResult.Decision.PASS
                    || ruleResult.decision() == GoalValidationRuleResult.Decision.ABSTAIN;
        }
    }
}
