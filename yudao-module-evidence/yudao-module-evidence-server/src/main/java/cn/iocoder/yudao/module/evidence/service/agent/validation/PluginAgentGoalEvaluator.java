package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentGoalEvaluator;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线 Goal Validation Pipeline。
 *
 * <p>先执行当前领域注册的确定性验证规则；规则只能 fail-closed 或要求补信息。
 * 所有规则通过/不适用后，仍交给独立 LLM Goal Evaluator 做通用充分性判断。</p>
 */
@Primary
@Component
public class PluginAgentGoalEvaluator implements AgentGoalEvaluator {

    private final DomainPluginResolver<GoalValidationRulePlugin> resolver;
    private final AgentGoalEvaluator fallback;

    public PluginAgentGoalEvaluator(List<GoalValidationRulePlugin> rules,
                                    @Qualifier("llmAgentGoalEvaluator") AgentGoalEvaluator fallback) {
        this.resolver = new DomainPluginResolver<>(rules);
        this.fallback = fallback;
    }

    @Override
    public Evaluation evaluate(String originalGoal,
                               List<AgentObservation> observations,
                               List<String> deterministicAnswers,
                               List<Evidence> evidences,
                               CapabilityInvocationContext context) {
        GoalValidationContext validationContext = new GoalValidationContext(
                originalGoal, observations, deterministicAnswers, evidences, context);
        for (GoalValidationRulePlugin plugin : resolver.resolve(pluginContext(context))) {
            GoalValidationRuleResult result;
            try {
                result = plugin.validate(validationContext);
            } catch (Exception e) {
                return Evaluation.failed("validation plugin " + plugin.pluginId() + " failed: "
                        + e.getClass().getSimpleName());
            }
            if (result == null || result.decision() == GoalValidationRuleResult.Decision.ABSTAIN
                    || result.decision() == GoalValidationRuleResult.Decision.PASS) {
                continue;
            }
            if (result.decision() == GoalValidationRuleResult.Decision.INSUFFICIENT) {
                return Evaluation.insufficient(prefix(plugin, result.reason()));
            }
            if (result.decision() == GoalValidationRuleResult.Decision.NEED_MORE_INFO) {
                return Evaluation.needMoreInfo(prefix(plugin, result.reason()), result.message());
            }
            return Evaluation.failed(prefix(plugin, result.reason()));
        }
        return fallback.evaluate(originalGoal, observations, deterministicAnswers, evidences, context);
    }

    @Override
    public boolean consumesLlmCall() {
        // 保守预算：规则可能提前 fail-closed，但只要存在 LLM fallback，就按一次模型验证预算计算。
        return fallback.consumesLlmCall();
    }

    private DomainPluginContext pluginContext(CapabilityInvocationContext context) {
        if (context == null) return DomainPluginContext.of("GENERAL");
        return new DomainPluginContext(context.tenantId(), context.kbId(), context.domainCode(),
                Set.of("GOAL_VALIDATION"), Map.of());
    }

    private String prefix(GoalValidationRulePlugin plugin, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "validation rule rejected current proof" : reason;
        return "[" + plugin.pluginId() + "] " + safeReason;
    }
}
