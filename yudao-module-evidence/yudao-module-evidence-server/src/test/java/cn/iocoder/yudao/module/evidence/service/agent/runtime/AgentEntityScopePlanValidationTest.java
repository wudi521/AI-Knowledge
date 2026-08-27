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
    private final AgentExecutionBudget budget = new AgentExecutionBudget(5, 4, 5_000L);

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
                        "entityIds", candidateRef("resolve"),
                        "select", List.of("TITLE")
                ), "读取候选详情", Set.of("resolve"))
        ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertTrue(result.valid(), result.message());
    }

    @Test
    void rejectsCandidateMaterializationThatRepeatsLiteralFilter() {
        AgentExecutionPlan plan = new AgentExecutionPlan("candidate-filter", "帮我检索出来体替代印花的专利详情信息", 0,
                List.of(
                        new PlanNode("resolve", "knowledge_retrieval", Map.of("query", "体替代印花"),
                                "定位与体替代印花相关的候选", Set.of()),
                        new PlanNode("detail", "structured_query", Map.of(
                                "entityIds", candidateRef("resolve"),
                                "filter", Map.of(
                                        "field", "TITLE",
                                        "operator", "CONTAINS",
                                        "values", List.of("体替代印花")
                                ),
                                "select", List.of("TITLE", "APPLICATION_NO", "INVENTOR")
                        ), "筛选候选并返回详情", Set.of("resolve"))
                ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertFalse(result.valid());
        assertTrue(result.message().contains("verification/materialization boundary"));
        assertTrue(result.message().contains("move 'filter' to a downstream node"));
    }

    @Test
    void acceptsHardFilterOnlyAfterCandidateWasVerified() {
        AgentExecutionPlan plan = new AgentExecutionPlan("candidate-then-filter", "查询近似对象且满足硬条件的详情", 0,
                List.of(
                        new PlanNode("resolve", "knowledge_retrieval", Map.of("query", "近似名称"),
                                "定位候选", Set.of()),
                        new PlanNode("materialize", "structured_query", Map.of(
                                "entityIds", candidateRef("resolve"),
                                "select", List.of("TITLE", "APPLICANT")
                        ), "物化候选实体", Set.of("resolve")),
                        new PlanNode("filter", "structured_query", Map.of(
                                "entityIds", verifiedRef("materialize"),
                                "filter", Map.of(
                                        "field", "APPLICANT",
                                        "operator", "CONTAINS",
                                        "values", List.of("某公司")
                                ),
                                "select", List.of("TITLE", "APPLICANT")
                        ), "应用独立硬条件", Set.of("materialize"))
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

    @Test
    void rejectsReplanStyleSelfReference() {
        AgentExecutionPlan plan = new AgentExecutionPlan("replan-self-ref", "查询候选详情", 1, List.of(
                new PlanNode("n1", "structured_query", Map.of(
                        "entityIds", candidateRef("n1"),
                        "select", List.of("TITLE")
                ), "读取上一轮候选详情", Set.of())
        ));

        AgentExecutionPlanValidator.Validation result = validator.validate(plan, budget);
        assertFalse(result.valid());
        assertTrue(result.message().contains("current-plan $ref cannot reference its own node"));
        assertTrue(result.message().contains("local to the current execution plan"));
    }

    private Map<String, Object> candidateRef(String nodeId) {
        return entityRef(nodeId, "candidateEntityIds");
    }

    private Map<String, Object> verifiedRef(String nodeId) {
        return entityRef(nodeId, "verifiedEntityIds");
    }

    private Map<String, Object> entityRef(String nodeId, String selector) {
        return Map.of(
                "$ref", nodeId,
                "selector", selector,
                "required", true,
                "expect", "LIST"
        );
    }
}
