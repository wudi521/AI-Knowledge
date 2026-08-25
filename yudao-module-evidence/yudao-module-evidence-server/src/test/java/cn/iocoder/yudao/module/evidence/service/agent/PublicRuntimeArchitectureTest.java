package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.module.evidence.service.agent.runtime.ActivityRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlan;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlanValidator;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentExecutionPlanner;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeExecutor;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.AgentRuntimeResult;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanArgumentResolver;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.PlanNode;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ProvenanceRecord;
import cn.iocoder.yudao.module.evidence.service.agent.runtime.ReferenceRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 封板测试：公共 Runtime 只能依赖通用 Agent/Tool/Runtime 契约，不能反向依赖旧 intent 或具体领域实现。
 */
class PublicRuntimeArchitectureTest {
    private static final List<String> FORBIDDEN_TYPE_FRAGMENTS = List.of(
            ".service.planner.v3.",
            ".service.structured.patent.",
            ".service.structured.product.",
            "PatentClaimLookupCapability",
            "EvidenceQueryEngineV3Facade",
            "QueryIntentV3"
    );

    private static final List<Class<?>> PUBLIC_RUNTIME_CORE = List.of(
            AgenticKnowledgeRuntimeEngine.class,
            AgentExecutionPlanner.class,
            AgentExecutionPlan.class,
            AgentExecutionPlanValidator.class,
            PlanNode.class,
            PlanArgumentResolver.class,
            AgentRuntimeExecutor.class,
            AgentRuntimeResult.class,
            ActivityRecord.class,
            ReferenceRecord.class,
            ProvenanceRecord.class
    );

    @Test
    void publicRuntimeMustNotDependOnLegacyIntentOrConcreteDomainImplementation() {
        for (Class<?> type : PUBLIC_RUNTIME_CORE) {
            for (Field field : type.getDeclaredFields()) assertAllowed(type, field.getGenericType().getTypeName());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (var parameter : constructor.getGenericParameterTypes()) assertAllowed(type, parameter.getTypeName());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertAllowed(type, method.getGenericReturnType().getTypeName());
                for (var parameter : method.getGenericParameterTypes()) assertAllowed(type, parameter.getTypeName());
            }
        }
    }

    private void assertAllowed(Class<?> owner, String typeName) {
        for (String forbidden : FORBIDDEN_TYPE_FRAGMENTS) {
            assertFalse(typeName.contains(forbidden),
                    () -> "public runtime type " + owner.getName() + " leaked forbidden dependency: " + typeName);
        }
    }
}
