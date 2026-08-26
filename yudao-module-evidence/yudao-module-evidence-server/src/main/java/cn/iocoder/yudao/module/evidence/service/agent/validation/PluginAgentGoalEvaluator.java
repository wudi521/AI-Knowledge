package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentGoalEvaluator;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在线 Goal Evaluator 兼容门面。
 *
 * <p>确定性领域验证规则统一交给 {@link GoalValidationRulePipeline} 编排；
 * 规则全部通过/不适用后，再交给独立 LLM Goal Evaluator 做通用证明充分性判断。
 * 本类不再维护插件发现、排序或领域匹配逻辑。</p>
 */
@Primary
@Component
public class PluginAgentGoalEvaluator implements AgentGoalEvaluator {

    private final GoalValidationRulePipeline pipeline;
    private final AgentGoalEvaluator fallback;

    public PluginAgentGoalEvaluator(GoalValidationRulePipeline pipeline,
                                    @Qualifier("llmAgentGoalEvaluator") AgentGoalEvaluator fallback) {
        this.pipeline = pipeline;
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
        GoalValidationRulePipeline.Result pipelineResult = pipeline.validate(validationContext);
        if (pipelineResult.allowsFallbackEvaluator()) {
            return fallback.evaluate(originalGoal, observations, deterministicAnswers, evidences, context);
        }

        GoalValidationRuleResult result = pipelineResult.ruleResult();
        String reason = prefix(pipelineResult.terminalPluginId(), result == null ? null : result.reason());
        if (result != null && result.decision() == GoalValidationRuleResult.Decision.INSUFFICIENT) {
            return Evaluation.insufficient(reason);
        }
        if (result != null && result.decision() == GoalValidationRuleResult.Decision.NEED_MORE_INFO) {
            return Evaluation.needMoreInfo(reason, result.message());
        }
        return Evaluation.failed(reason);
    }

    @Override
    public boolean consumesLlmCall() {
        // 保守预算：领域规则可能提前 fail-closed，但只要存在 LLM fallback，就按一次模型验证预算计算。
        return fallback.consumesLlmCall();
    }

    private String prefix(String pluginId, String reason) {
        String safePluginId = pluginId == null || pluginId.isBlank() ? "goal-validation" : pluginId;
        String safeReason = reason == null || reason.isBlank() ? "validation rule rejected current proof" : reason;
        return "[" + safePluginId + "] " + safeReason;
    }
}
