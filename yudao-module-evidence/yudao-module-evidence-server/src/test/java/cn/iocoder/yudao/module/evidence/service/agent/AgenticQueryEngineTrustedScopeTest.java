package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenticQueryEngineTrustedScopeTest {

    @Test
    void verifiedStructuredEntityMustBecomeTrustedScopeForNextStep() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                assertTrue(context.contextEntityIds().isEmpty());
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "trusted-entity",
                        Map.of("query", "申请号X"), "精确定位目标对象", null);
            }
            assertEquals(List.of(74L), context.contextEntityIds());
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "回答原始问题", null);
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new TrustedEntityCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "申请号X的公布号是什么？", 6L, "PATENT", 1L, 2L, "trace-trusted", List.of());
            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals(List.of(74L), result.verifiedEntityIds());
            assertEquals("公布号=CN123", result.answer());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void aggregateFactMustNotPromoteParticipatingEntitiesIntoTrustedScope() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                assertTrue(context.contextEntityIds().isEmpty());
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "aggregate-count",
                        Map.of("query", "统计"), "统计专利数量", null);
            }
            assertTrue(context.contextEntityIds().isEmpty(),
                    "COUNT/AGGREGATE provenance must not become pronoun-addressable trusted entity scope");
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "回答统计结果", null);
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new AggregateCountCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "现在专利库有多少专利？", 6L, "PATENT", 1L, 2L,
                    "trace-aggregate-scope", List.of());
            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals("专利数量=2", result.answer());
            assertTrue(result.verifiedEntityIds().isEmpty());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void valueProjectionMustNotPromoteRepresentativeEntitiesIntoTrustedScope() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "value-projection",
                        Map.of("query", "不同申请人"), "返回去重后的字段值集合", null);
            }
            assertTrue(context.contextEntityIds().isEmpty(),
                    "DISTINCT/exploded field values are values, not pronoun-addressable entity scope");
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "回答值集合", null);
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new ValueProjectionCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "有哪些不同申请人？", 6L, "PATENT", 1L, 2L,
                    "trace-value-projection", List.of());
            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals("申请人=甲、乙", result.answer());
            assertTrue(result.verifiedEntityIds().isEmpty());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void semanticCandidateDocumentIdMustNeverBecomeTrustedScope() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "candidate-only",
                        Map.of("query", "名称相近的专利"), "获取候选证据", null);
            }
            assertTrue(context.contextEntityIds().isEmpty(),
                    "semantic retrieval candidate must remain evidence instead of trusted user scope");
            return new AgentDecision(AgentActionType.NEED_MORE_INFO, null, Map.of(), null, "需要可靠参照对象");
        };

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new CandidateOnlyCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, null);
            AgenticQueryEngine.Result result = engine.execute(
                    "现在专利库里面有名称相近的专利吗？", 6L, "PATENT", 1L, 2L,
                    "trace-candidate", List.of());
            assertEquals(AgenticQueryEngine.State.CLARIFY, result.state());
            assertTrue(result.verifiedEntityIds().isEmpty());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void deterministicFactsAndSemanticEvidenceMustComposeAgainstImmutableOriginalGoal() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentPlanner planner = (state, context, observations, history) -> {
            int call = plannerCalls.getAndIncrement();
            if (call == 0) {
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "trusted-entity",
                        Map.of("query", "申请号X"), "取得公布号", null);
            }
            if (call == 1) {
                assertEquals(List.of(74L), context.contextEntityIds());
                return new AgentDecision(AgentActionType.CALL_CAPABILITY, "candidate-only",
                        Map.of("query", "技术方案", "scope", "CONTEXT"), "解释技术方案", null);
            }
            return new AgentDecision(AgentActionType.ANSWER, null, Map.of(), "合并完整答案", null);
        };

        AnswerPipeline answerPipeline = mock(AnswerPipeline.class);
        GenerationResult generation = mock(GenerationResult.class);
        when(generation.getAnswer()).thenReturn("技术方案=通过可信对象范围内证据生成");
        when(generation.isClaimFail()).thenReturn(false);
        when(answerPipeline.generateWithClaims(
                eq("申请号X的公布号是什么，并说明它的技术方案？"), anyList(), anyList())).thenReturn(generation);

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new TrustedEntityCapability(), new CandidateOnlyCapability()), List.of()));
        try {
            AgenticQueryEngine engine = new AgenticQueryEngine(planner, invoker, answerPipeline);
            AgenticQueryEngine.Result result = engine.execute(
                    "申请号X的公布号是什么，并说明它的技术方案？",
                    6L, "PATENT", 1L, 2L, "trace-composite", List.of());
            assertEquals(AgenticQueryEngine.State.ANSWER, result.state());
            assertEquals("公布号=CN123\n技术方案=通过可信对象范围内证据生成", result.answer());
            assertEquals(List.of(74L), result.verifiedEntityIds());
            assertTrue(result.traceSteps().stream()
                    .filter(step -> "ANSWER".equals(step.phase()))
                    .anyMatch(step -> step.summary().contains("evidenceCount=1")
                            && step.summary().contains("originalGoal")));
        } finally {
            invoker.shutdown();
        }
    }

    private static final class TrustedEntityCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "trusted-entity", "1", "精确结构化实体测试能力", Set.of("query"), true, 1000, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "entityId=74, publicationNo=CN123"; }
                @Override public String progressHash() { return "entity-74"; }
                @Override public List<Long> verifiedEntityIds() { return List.of(74L); }
                @Override public String deterministicAnswer() { return "公布号=CN123"; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1));
        }
    }

    private static final class AggregateCountCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "aggregate-count", "1", "聚合 trusted scope 防污染测试能力", Set.of("query"), true, 1000, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "count=2; sourceEntities=[74,75]"; }
                @Override public String progressHash() { return "count-2"; }
                @Override public List<Long> verifiedEntityIds() { return List.of(74L, 75L); }
                @Override public String deterministicAnswer() { return "专利数量=2"; }
            };
            return CapabilityResult.success(output, Map.of("task", "COUNT", "outputCount", 1));
        }
    }

    private static final class ValueProjectionCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "value-projection", "1", "值投影 trusted scope 防污染测试能力", Set.of("query"), true, 1000, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "distinct applicants from entities 74,75"; }
                @Override public String progressHash() { return "applicants-a-b"; }
                @Override public List<Long> verifiedEntityIds() { return List.of(74L, 75L); }
                @Override public String deterministicAnswer() { return "申请人=甲、乙"; }
            };
            return CapabilityResult.success(output, Map.of(
                    "valueProjection", true,
                    "outputCount", 2));
        }
    }

    private static final class CandidateOnlyCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "candidate-only", "1", "语义候选测试能力", Set.of("query"), true, 1000, 10);

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            Evidence candidate = Evidence.builder()
                    .chunkId(100L)
                    .documentId("74")
                    .documentName("倾转小翼垂直起降固定翼无人机")
                    .content("candidate")
                    .score(1D)
                    .products(List.of())
                    .channels(List.of("vector"))
                    .build();
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "candidate documentId=74"; }
                @Override public String progressHash() { return "candidate-74"; }
                @Override public List<Evidence> evidences() { return List.of(candidate); }
            };
            return CapabilityResult.success(output, Map.of("evidenceCount", 1));
        }
    }
}
