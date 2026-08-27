package cn.iocoder.yudao.module.evidence.service.agent.validation;

import cn.iocoder.yudao.module.evidence.service.agent.AgentObservation;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalValidationRulePipelineTest {

    @Test
    void exactDomainRuleRunsBeforeGenericAndPassContinues() {
        List<String> calls = new ArrayList<>();
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of(
                rule("generic", Set.of("*"), calls, GoalValidationRuleResult.insufficient("generic terminal")),
                rule("patent", Set.of("PATENT"), calls, GoalValidationRuleResult.pass("patent passed"))));

        GoalValidationRulePipeline.Result result = pipeline.validate(context("PATENT"));

        assertEquals(List.of("patent", "generic"), calls);
        assertEquals("generic", result.terminalPluginId());
        assertEquals(GoalValidationRuleResult.Decision.INSUFFICIENT, result.ruleResult().decision());
    }

    @Test
    void firstTerminalRuleStopsRemainingRulesFailClosed() {
        List<String> calls = new ArrayList<>();
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of(
                orderedRule("first", Set.of("PATENT"), 10, calls,
                        GoalValidationRuleResult.needMoreInfo("missing identifier", "请补充标识")),
                orderedRule("second", Set.of("PATENT"), 20, calls,
                        GoalValidationRuleResult.pass("must not run"))));

        GoalValidationRulePipeline.Result result = pipeline.validate(context("PATENT"));

        assertEquals(List.of("first"), calls);
        assertEquals(GoalValidationRuleResult.Decision.NEED_MORE_INFO, result.ruleResult().decision());
        assertFalse(result.allowsFallbackEvaluator());
    }

    @Test
    void noApplicableRulesAllowsIndependentFallbackEvaluator() {
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of(
                rule("patent", Set.of("PATENT"), new ArrayList<>(), GoalValidationRuleResult.insufficient("x"))));

        GoalValidationRulePipeline.Result result = pipeline.validate(context("CONTRACT"));

        assertTrue(result.allowsFallbackEvaluator());
        assertTrue(result.executedPluginIds().isEmpty());
    }

    @Test
    void incompleteSuccessfulCoverageProofMustFailClosedBeforeDomainRules() {
        List<String> calls = new ArrayList<>();
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of(
                rule("must-not-run", Set.of("PATENT"), calls, GoalValidationRuleResult.pass("x"))));
        AgentObservation malformedSuccess = AgentObservation.success(
                "structured_query", "global operation", "partial", "ref-incomplete",
                Map.of("completeDataset", true,
                        "sourceTruncated", true,
                        "missingValueCount", 0));

        GoalValidationRulePipeline.Result result = pipeline.validate(
                context("PATENT", List.of(malformedSuccess)));

        assertEquals("runtime-coverage-contract", result.terminalPluginId());
        assertEquals(GoalValidationRuleResult.Decision.INSUFFICIENT, result.ruleResult().decision());
        assertTrue(result.ruleResult().reason().contains("INCOMPLETE_DATASET"));
        assertFalse(result.allowsFallbackEvaluator());
        assertTrue(calls.isEmpty());
    }

    @Test
    void completeCoverageProofAllowsNormalGoalEvaluationFlow() {
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of());
        AgentObservation complete = AgentObservation.success(
                "structured_query", "global operation", "complete", "ref-complete",
                Map.of("completeDataset", true,
                        "sourceTruncated", false,
                        "missingValueCount", 0));

        GoalValidationRulePipeline.Result result = pipeline.validate(
                context("PATENT", List.of(complete)));

        assertTrue(result.allowsFallbackEvaluator());
        assertEquals(GoalValidationRuleResult.Decision.PASS, result.ruleResult().decision());
    }

    @Test
    void failedCoverageAttemptMustNotPoisonLaterReplanForever() {
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of());
        AgentObservation failedAttempt = new AgentObservation(
                "structured_query", "global operation", "timeout", "failed-1", "ERROR",
                false, false, false, AgentStopReason.TIME_BUDGET_EXCEEDED.name(),
                Map.of(AgentObservation.META_REQUIRED_COVERAGE, AgentObservation.COVERAGE_COMPLETE,
                        AgentObservation.META_COVERAGE_COMPLETE, false,
                        AgentObservation.META_SOURCE_TRUNCATED, false));

        GoalValidationRulePipeline.Result result = pipeline.validate(
                context("PATENT", List.of(failedAttempt)));

        assertTrue(result.allowsFallbackEvaluator());
    }

    @Test
    void localEvidenceWithoutCoverageContractMustNotBeForcedToFullDataset() {
        GoalValidationRulePipeline pipeline = new GoalValidationRulePipeline(List.of());
        AgentObservation local = AgentObservation.success(
                "knowledge_retrieval", "retrieve local evidence", "five chunks", "ref-local",
                Map.of("outputCount", 5));

        GoalValidationRulePipeline.Result result = pipeline.validate(
                context("PATENT", List.of(local)));

        assertTrue(result.allowsFallbackEvaluator());
    }

    private GoalValidationContext context(String domain) {
        return context(domain, List.of());
    }

    private GoalValidationContext context(String domain, List<AgentObservation> observations) {
        CapabilityInvocationContext invocation = new CapabilityInvocationContext(
                1L, 2L, 3L, domain, "t", Set.of(), Set.of(), List.of(), "test", false);
        return new GoalValidationContext("goal", observations, List.of(), List.of(), invocation);
    }

    private GoalValidationRulePlugin rule(String id, Set<String> domains, List<String> calls,
                                          GoalValidationRuleResult result) {
        return orderedRule(id, domains, 0, calls, result);
    }

    private GoalValidationRulePlugin orderedRule(String id, Set<String> domains, int order, List<String> calls,
                                                 GoalValidationRuleResult result) {
        return new GoalValidationRulePlugin() {
            @Override public String pluginId() { return id; }
            @Override public Set<String> supportedDomains() { return domains; }
            @Override public int order() { return order; }
            @Override public GoalValidationRuleResult validate(GoalValidationContext context) {
                calls.add(id);
                return result;
            }
        };
    }
}
