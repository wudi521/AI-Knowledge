package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * AgentExecutionPlan 中的一个事实操作节点。
 *
 * <p>节点只声明 capability、arguments、purpose 和依赖关系；tenant/user/kb/permission/budget
 * 等系统边界由 Runtime 注入，Planner 无权写入。</p>
 */
public record PlanNode(String id,
                       String capability,
                       Map<String, Object> arguments,
                       String purpose,
                       Set<String> dependsOn) {
    public PlanNode {
        arguments = arguments == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        dependsOn = dependsOn == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn));
    }

    public static PlanNode of(String id, String capability, Map<String, Object> arguments, String purpose) {
        return new PlanNode(id, capability, arguments, purpose, Set.of());
    }
}
