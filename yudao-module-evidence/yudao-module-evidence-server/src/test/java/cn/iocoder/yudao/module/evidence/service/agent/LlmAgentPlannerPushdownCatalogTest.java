package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownCoordinator;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPushdownCapabilityProvider;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAgentPlannerPushdownCatalogTest {

    @Test
    void plannerInputContainsRuntimeTypedPushdownCapabilities() {
        ModelApi modelApi = mock(ModelApi.class);
        PromptSupport promptSupport = mock(PromptSupport.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        when(promptSupport.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(capabilityRegistry.listDefinitions(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(modelApi.chat(org.mockito.ArgumentMatchers.any())).thenReturn(CommonResult.success(
                "{\"action\":\"STOP\",\"capability\":null,\"arguments\":{},\"purpose\":null,\"message\":\"done\"}"));

        StructuredPushdownCoordinator coordinator = new StructuredPushdownCoordinator(
                List.of(), List.of(new PatentStructuredPushdownCapabilityProvider()));
        LlmAgentPlanner planner = new LlmAgentPlanner(
                modelApi, promptSupport, capabilityRegistry, null, null, coordinator);

        planner.decide(new AgentExecutionState("哪个专利标题最长？"),
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-1"),
                List.of(), List.of());

        ArgumentCaptor<ModelChatReqDTO> captor = ArgumentCaptor.forClass(ModelChatReqDTO.class);
        verify(modelApi).chat(captor.capture());
        String input = captor.getValue().getUser();
        assertTrue(input.contains("pushdownCapabilities="));
        assertTrue(input.contains("ORDER_TOP_N"));
        assertTrue(input.contains("TITLE"));
        assertTrue(input.contains("LENGTH"));
        assertTrue(input.contains("PATENT_COUNT"));
    }
}
