package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import com.alibaba.ttl.TransmittableThreadLocal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 防止 Runtime 两次异步切线程时丢失登录、租户、trace 等 TTL 请求上下文。 */
class AgentAsyncContextPropagationTest {

    @Test
    void propagatesRequestContextAcrossRuntimeAndCapabilityExecutors() {
        TransmittableThreadLocal<String> requestContext = new TransmittableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>();
        requestContext.set("request-user-context");

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(new ContextProbeCapability(requestContext, observed)), List.of()));
        AgentRuntimeExecutor runtime = new AgentRuntimeExecutor(invoker);
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan("ctx-1", "验证异步请求上下文传播", 0, List.of(
                    new PlanNode("probe", "context-probe", Map.of(), "读取请求上下文", Set.of())
            ));

            AgentRuntimeResult result = runtime.execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-context"),
                    new AgentExecutionBudget(6, 6, 5_000L));

            assertEquals(CapabilityResultStatus.SUCCESS, result.status());
            assertEquals("request-user-context", observed.get());
        } finally {
            requestContext.remove();
            runtime.shutdown();
            invoker.shutdown();
        }
    }

    private static final class ContextProbeCapability implements KnowledgeCapability {
        private final TransmittableThreadLocal<String> requestContext;
        private final AtomicReference<String> observed;
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "context-probe", "1", "读取异步请求上下文", Set.of(), true, 1_000L, 10);

        private ContextProbeCapability(TransmittableThreadLocal<String> requestContext,
                                       AtomicReference<String> observed) {
            this.requestContext = requestContext;
            this.observed = observed;
        }

        @Override
        public CapabilityDefinition definition() {
            return definition;
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            observed.set(requestContext.get());
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "context-propagated"; }
                @Override public String progressHash() { return "context-propagated"; }
            };
            return CapabilityResult.success(output, Map.of("outputCount", 1));
        }
    }
}
