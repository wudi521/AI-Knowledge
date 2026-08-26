package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentGoalEvaluator;
import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginAgentGoalEvaluatorTest {

    @Test
    void exactDomainRuleCanFailClosedBeforeFallbackEvaluator() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AgentGoalEvaluator fallback = fallback(fallbackCalls);
        GoalValidationRulePlugin patentRule = rule("patent-proof", Set.of("PATENT"),
                GoalValidationRuleResult.insufficient("missing authoritative patent proof"));
        PluginAgentGoalEvaluator evaluator = new PluginAgentGoalEvaluator(List.of(patentRule), fallback);

        AgentGoalEvaluator.Evaluation evaluation = evaluator.evaluate("goal", List.of(), List.of(), List.of(), context("PATENT"));

        assertEquals(AgentGoalEvaluator.Verdict.INSUFFICIENT, evaluation.verdict());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void unrelatedDomainRuleIsIgnoredAndGenericEvaluatorStillRuns() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AgentGoalEvaluator fallback = fallback(fallbackCalls);
        GoalValidationRulePlugin patentRule = rule("patent-proof", Set.of("PATENT"),
                GoalValidationRuleResult.insufficient("should not run"));
        PluginAgentGoalEvaluator evaluator = new PluginAgentGoalEvaluator(List.of(patentRule), fallback);

        AgentGoalEvaluator.Evaluation evaluation = evaluator.evaluate("goal", List.of(), List.of(), List.of(), context("CONTRACT"));

        assertEquals(AgentGoalEvaluator.Verdict.SATISFIED, evaluation.verdict());
        assertEquals(1, fallbackCalls.get());
    }

    private GoalValidationRulePlugin rule(String id, Set<String> domains, GoalValidationRuleResult result) {
        return new GoalValidationRulePlugin() {
            @Override public String pluginId() { return id; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public GoalValidationRuleResult validate(GoalValidationContext context) { return result; }
        };
    }

    private AgentGoalEvaluator fallback(AtomicInteger calls) {
        return new AgentGoalEvaluator() {
            @Override
            public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                       List<String> deterministicAnswers, List<Evidence> evidences,
                                       CapabilityInvocationContext context) {
                calls.incrementAndGet();
                return Evaluation.satisfied("fallback");
            }

            @Override public boolean consumesLlmCall() { return true; }
        };
    }

    private CapabilityInvocationContext context(String domain) {
        return new CapabilityInvocationContext(1L, 2L, 3L, domain, "t", Set.of(), Set.of(), List.of(), "test", false);
    }
}
