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
    void plannerReceivesQueryIrLanguageFromRuntimePluginInsteadOfSemanticCombinationMatrix() {
        ModelApi modelApi = mock(ModelApi.class);
        PromptSupport promptSupport = mock(PromptSupport.class);
        when(promptSupport.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(modelApi.chat(org.mockito.ArgumentMatchers.any())).thenReturn(CommonResult.success(
                "{\"action\":\"STOP\",\"message\":\"done\"}"));

        Map<String, String> machineSchema = new LinkedHashMap<>();
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

        planner.plan(new AgentExecutionState("哪个申请人的平均专利标题长度最长？"),
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-ir"),
                List.of(), List.of(), List.of(), 0, 4);

        ArgumentCaptor<ModelChatReqDTO> captor = ArgumentCaptor.forClass(ModelChatReqDTO.class);
        verify(modelApi).chat(captor.capture());
        String input = captor.getValue().getUser();

        assertThat(input).contains("QUERY_IR_V1", "GROUP_BY", "AGGREGATE", "AVG", "ORDER_BY");
        assertThat(input).doesNotContain("ORDER_TOP_N", "PATENT_COUNT", "legacy task", "legacy operation");
        assertThat(registry.listDefinitions(new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-ir"))
                .get(0).argumentSchema()).doesNotContainKeys("task", "operation", "metric");
    }
}
