package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEntityScopePlanValidationTest {

    private final AgentExecutionPlanValidator validator = new AgentExecutionPlanValidator();
    private final AgentExecutionBudget budget = new AgentExecutionBudget(4, 4, 5_000L);

    @Test
    void rejectsLiteralInternalEntityIds() {
        AgentExecutionPlan plan = new AgentExecutionPlan("literal-ids", "查询候选详情", 0, List.of(
                new PlanNode("detail", "structured_query",
                        Map.of("entityIds", List.of(66L), "select", List.of("TITLE")),
                        "读取详情", Set.of())
        ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertFalse(result.valid());
        assertTrue(result.message().contains("literal internal entity ids are forbidden"));
    }

    @Test
    void acceptsCandidateEntityIdsWithExplicitTypedReference() {
        AgentExecutionPlan plan = new AgentExecutionPlan("candidate-scope", "查询候选详情", 0, List.of(
                new PlanNode("resolve", "knowledge_retrieval", Map.of("query", "近似名称"),
                        "定位候选", Set.of()),
                new PlanNode("detail", "structured_query", Map.of(
                        "entityIds", Map.of(
                                "$ref", "resolve",
                                "selector", "candidateEntityIds",
                                "required", true,
                                "expect", "LIST"
                        ),
                        "select", List.of("TITLE")
                ), "读取候选详情", Set.of("resolve"))
        ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertTrue(result.valid(), result.message());
    }

    @Test
    void rejectsEntityScopeReferenceUsingNonEntitySelector() {
        AgentExecutionPlan plan = new AgentExecutionPlan("wrong-selector", "查询候选详情", 0, List.of(
                new PlanNode("resolve", "knowledge_retrieval", Map.of("query", "近似名称"),
                        "定位候选", Set.of()),
                new PlanNode("detail", "structured_query", Map.of(
                        "entityIds", Map.of(
                                "$ref", "resolve",
                                "selector", "metadata",
                                "path", "candidateEntityIds",
                                "required", true,
                                "expect", "LIST"
                        ),
                        "select", List.of("TITLE")
                ), "读取候选详情", Set.of("resolve"))
        ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertFalse(result.valid());
        assertTrue(result.message().contains("candidateEntityIds or verifiedEntityIds"));
    }
}
