package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlan;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlanner;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentPlanningDecision;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeExecutor;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full proof may need semantic/support facts, while user-facing answer content can be fully deterministic.
 * Support-only evidence must not force AnswerPipeline or get concatenated into the final answer.
 */
class AgentAnswerReferenceRoleTest {

    @Test
    void supportOnlySemanticReferenceDoesNotBlockDeterministicAnswerFastPath() {
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new SupportEvidenceCapability(), new DetailCapability()), List.of()));
        try {
            AgentExecutionPlanner planner = (state, context, observations, references, history, replanAttempt, maxPlanNodes) ->
                    AgentPlanningDecision.execute(new AgentExecutionPlan(
                            "typed-proof-plan", state.getOriginalGoal(), replanAttempt,
                            List.of(
                                    new PlanNode("resolve", "semantic-support", Map.of(),
                                            "建立近似称呼与候选对象的支持关系", Set.of()),
                                    new PlanNode("detail", "deterministic-detail", Map.of(),
                                            "返回用户要求的结构化详情", Set.of())
                            )));

            AgentGoalEvaluator evaluator = new AgentGoalEvaluator() {
                @Override
                public Evaluation evaluate(String originalGoal, List<AgentObservation> observations,
                                           List<String> deterministicAnswers, List<Evidence> evidences,
                                           CapabilityInvocationContext context) {
                    return Evaluation.satisfied(
                            "语义 Reference 负责实体消歧，结构化 Reference 负责最终详情",
                            List.of("typed-proof-plan:resolve", "typed-proof-plan:detail"),
                            List.of("typed-proof-plan:detail"));
                }

                @Override
                public boolean consumesLlmCall() {
                    return false;
                }
            };

            // null 是刻意的：如果 support-only semantic Evidence 仍被送进回答路径，执行会 STOPPED。
            AgenticKnowledgeRuntimeEngine engine = new AgenticKnowledgeRuntimeEngine(
                    planner, new AgentRuntimeExecutor(invoker), evaluator, null, new EvidenceProperties());

            AgenticKnowledgeRuntimeEngine.Result result = engine.execute(
                    "帮我检索出来体替代印花的专利详情信息",
                    6L, "PATENT", 1L, 2L, "trace-answer-role", List.of());

            assertEquals(AgenticKnowledgeRuntimeEngine.State.ANSWER, result.state());
            assertEquals("查询结果：一种代替印花的运动服；申请号=202311042981.1；发明人=孙新玲", result.answer());
            assertTrue(result.evidences().isEmpty(), "support-only Evidence 不得进入用户答案内容范围");
            assertTrue(result.traceSteps().stream().anyMatch(step ->
                    "RESULT_EVALUATION".equals(step.phase())
                            && step.summary() != null
                            && step.summary().contains("proofFrontier=[typed-proof-plan:resolve, typed-proof-plan:detail]")
                            && step.summary().contains("answerFrontier=[typed-proof-plan:detail]")));
            assertTrue(result.traceSteps().stream().anyMatch(step ->
                    "ANSWER_VALIDATION".equals(step.phase())
                            && step.summary() != null
                            && step.summary().contains("deterministicFastPath=true")
                            && step.summary().contains("answerPipelineSkipped=true")));
        } finally {
            invoker.shutdown();
        }
    }

    private static final class SupportEvidenceCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "semantic-support", "1", "只提供实体消歧支持证据", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            Evidence evidence = Evidence.builder()
                    .chunkId(1L).documentId("66").documentName("一种代替印花的运动服")
                    .content("体替代印花的近似称呼命中该专利正文")
                    .score(1D).build();
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "semantic entity-resolution support"; }
                @Override public String progressHash() { return "semantic-support:66"; }
                @Override public List<Evidence> evidences() { return List.of(evidence); }
                @Override public List<Long> candidateEntityIds() { return List.of(66L); }
            };
            return CapabilityResult.success(output, Map.of("entityTrust", "CANDIDATE", "outputComplete", false));
        }
    }

    private static final class DetailCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "deterministic-detail", "1", "结构化详情", Set.of(), true, 1_000L, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            String answer = "查询结果：一种代替印花的运动服；申请号=202311042981.1；发明人=孙新玲";
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return answer; }
                @Override public String progressHash() { return "detail:66"; }
                @Override public String deterministicAnswer() { return answer; }
                @Override public List<Long> verifiedEntityIds() { return List.of(66L); }
            };
            return CapabilityResult.success(output, Map.of("completeDataset", true, "outputCount", 1));
        }
    }
}
