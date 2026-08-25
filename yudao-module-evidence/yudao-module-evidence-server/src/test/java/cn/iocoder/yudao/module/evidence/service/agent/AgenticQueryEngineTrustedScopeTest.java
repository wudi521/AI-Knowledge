package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
