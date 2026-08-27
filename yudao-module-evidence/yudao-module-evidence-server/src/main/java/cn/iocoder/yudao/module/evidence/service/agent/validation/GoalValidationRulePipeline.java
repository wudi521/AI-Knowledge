package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 Goal Validation Rule Pipeline。
 *
 * <p>先执行与领域无关的 Runtime proof contract，再编排当前领域的确定性证明规则；
 * PASS/ABSTAIN 继续，INSUFFICIENT/NEED_MORE_INFO/FAILED fail-closed。
 * 最终通用充分性判断仍由独立 Goal Evaluator 完成。</p>
 */
@Component
public class GoalValidationRulePipeline {
    private static final String COVERAGE_CONTRACT_PLUGIN = "runtime-coverage-contract";

    private final DomainPluginResolver<GoalValidationRulePlugin> resolver;

    public GoalValidationRulePipeline(List<GoalValidationRulePlugin> rules) {
        this.resolver = new DomainPluginResolver<>(rules);
    }

    public Result validate(GoalValidationContext context) {
        Result coverageFailure = validateCoverageContract(context);
        if (coverageFailure != null) return coverageFailure;

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

    /**
     * 对已经进入事实池的成功 Observation 做第二次防御性校验。
     *
     * <p>这里只读取机器合同 requiredCoverage/coverageComplete 等字段，绝不从 originalGoal、purpose、summary
     * 猜“最长/最大/第一”等自然语言意图。失败 Observation 不在这里永久污染后续 replan；它们由 Runtime/Planner
     * 的失败通道处理。只有一个被当成成功事实引用的结果违反自己的覆盖合同，才会 fail-closed。</p>
     */
    private Result validateCoverageContract(GoalValidationContext context) {
        if (context == null || context.observations() == null) return null;
        for (AgentObservation observation : context.observations()) {
            if (observation == null || !"SUCCESS".equalsIgnoreCase(observation.status())) continue;
            Map<String, Object> metadata = observation.metadata();
            if (metadata == null) continue;
            if (!AgentObservation.COVERAGE_COMPLETE.equals(String.valueOf(
                    metadata.get(AgentObservation.META_REQUIRED_COVERAGE)))) {
                continue;
            }

            boolean coverageComplete = Boolean.TRUE.equals(metadata.get(AgentObservation.META_COVERAGE_COMPLETE));
            boolean sourceTruncated = Boolean.TRUE.equals(metadata.get(AgentObservation.META_SOURCE_TRUNCATED));
            int missingValueCount = intMetadata(metadata.get("missingValueCount"), 0);
            if (!observation.completeDataset() || !coverageComplete || sourceTruncated || missingValueCount > 0) {
                String reason = "INCOMPLETE_DATASET: successful observation cannot prove required COMPLETE coverage"
                        + "; capability=" + safe(observation.capability())
                        + "; completeDataset=" + observation.completeDataset()
                        + "; coverageComplete=" + coverageComplete
                        + "; sourceTruncated=" + sourceTruncated
                        + "; missingValueCount=" + missingValueCount;
                return new Result(COVERAGE_CONTRACT_PLUGIN,
                        GoalValidationRuleResult.insufficient(reason),
                        List.of(COVERAGE_CONTRACT_PLUGIN));
            }
        }
        return null;
    }

    private int intMetadata(Object value, int defaultValue) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (Exception ignore) { }
        }
        return defaultValue;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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
