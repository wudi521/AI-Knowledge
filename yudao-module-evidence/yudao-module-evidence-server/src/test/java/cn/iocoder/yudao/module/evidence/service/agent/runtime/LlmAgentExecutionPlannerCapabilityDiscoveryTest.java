package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionState;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryLanguageCatalog;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredQueryLanguageCapabilityProvider;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAgentExecutionPlannerCapabilityDiscoveryTest {

    @Test
    void plannerReceivesQueryIrLanguageTypedDataflowBudgetClauseBindingAndDetailContract() {
        ModelApi modelApi = mock(ModelApi.class);
        PromptSupport promptSupport = mock(PromptSupport.class);
        when(promptSupport.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(modelApi.chat(org.mockito.ArgumentMatchers.any())).thenReturn(CommonResult.success(
                "{\"action\":\"STOP\",\"message\":\"done\"}"));

        Map<String, String> machineSchema = new LinkedHashMap<>();
        machineSchema.put("entityIds", "typed upstream candidate/verified entity scope");
        machineSchema.put("select", "select expressions");
        machineSchema.put("filter", "predicate tree");
        machineSchema.put("groupBy", "group expressions");
        machineSchema.put("aggregate", "aggregate expression");
        machineSchema.put("having", "aggregate filter");
        machineSchema.put("orderBy", "order expressions");
        machineSchema.put("distinct", "distinct output");
        machineSchema.put("limit", "output limit");
        machineSchema.put("task", "legacy task");
        machineSchema.put("operation", "legacy operation");
        machineSchema.put("metric", "legacy metric");

        CapabilityDefinition definition = new CapabilityDefinition(
                "structured_query", "2", "structured query", machineSchema, Set.of(),
                "STRUCTURED_RESULT", true, Set.of(), Set.of(), Set.of(), 8_000L, 50);
        KnowledgeCapability structured = new KnowledgeCapability() {
            @Override
            public CapabilityDefinition definition() {
                return definition;
            }

            @Override
            public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
                return CapabilityResult.success(Map.of(), Map.of("completeDataset", true, "outputCount", 0));
            }
        };

        StructuredQueryLanguageCatalog languageCatalog = new StructuredQueryLanguageCatalog(
                List.of(new PatentStructuredQueryLanguageCapabilityProvider()));
        CapabilityRegistry registry = new CapabilityRegistry(List.of(structured), List.of(), languageCatalog);
        LlmAgentExecutionPlanner planner = new LlmAgentExecutionPlanner(
                modelApi, promptSupport, registry, null, null);

        planner.plan(new AgentExecutionState("哪个专利发明人最多？哪个最少？罗列专利名字和发明人"),
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-ir"),
                List.of(), List.of(), List.of(), 0, 4);

        ArgumentCaptor<ModelChatReqDTO> captor = ArgumentCaptor.forClass(ModelChatReqDTO.class);
        verify(modelApi).chat(captor.capture());
        ModelChatReqDTO request = captor.getValue();
        String input = request.getUser();
        String system = request.getSystem();

        assertThat(input).contains("QUERY_IR_V1", "GROUP_BY", "AGGREGATE", "AVG", "ORDER_BY");
        assertThat(input).contains("dataflowContract=", "metadata.dataflowRows", "allowPartial",
                "remainingElapsedBudgetMs=", "replanPolicy=", "setFilterPolicy=", "entityResolutionPolicy=");
        assertThat(input).contains("originalGoal=哪个专利发明人最多？哪个最少？罗列专利名字和发明人");
        assertThat(input).doesNotContain("ORDER_TOP_N", "PATENT_COUNT", "legacy task", "legacy operation");
        assertThat(system).contains("最少必要节点", "remainingElapsedBudgetMs",
                "dataflowRows[*].groupKey", "dataflowRows[*].fields.<FIELD_CODE>",
                "禁止把整个 data/Output/summary/deterministicAnswer 塞进 values",
                "禁止把主题、职业背景、偏好、用途、推荐条件、语义相关性自行改写成 TITLE CONTAINS",
                "相关性、相似性、推荐、可参考性、主题探索", "knowledge_retrieval",
                "不要仅为了把“同一信息需求的两种召回方式”机械 UNION",
                "比较/分组主体", "统计指标", "省略句", "禁止把后续子句出现的属性反向污染前面的子句",
                "按专利分组统计发明人数取最大", "绝不能把前两个问题改成“哪个姓氏出现最多/最少”",
                "orderBy 每个排序项必须明确且仅明确一个排序来源", "aggregateValue=true",
                "硬纠错约束", "不得仅改 purpose/JSON 写法后重复语义等价的旧计划",
                "purpose 必须只描述该节点实际能够证明的事实", "后续投影节点",
                "必须使用 IN 或 OR", "禁止生成 field=A AND field=B", "PERSON_SURNAME", "explode=true",
                "structured_query 若在 argumentSchema 暴露 entityIds",
                "selector\":\"candidateEntityIds", "candidate 自动升级",
                "近似名称/错字/口语简称/不确定实体称呼", "n1=knowledge_retrieval", "n2=structured_query");
        assertThat(request.getScenario()).isEqualTo("agent-execution-plan-v6");
        CapabilityDefinition visible = registry.listDefinitions(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-ir")).get(0);
        assertThat(visible.argumentSchema()).containsKey("entityIds");
        assertThat(visible.argumentSchema()).doesNotContainKeys("task", "operation", "metric");
    }
}
