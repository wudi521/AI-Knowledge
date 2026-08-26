package cn.iocoder.yudao.module.evidence.service.agent.capability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CapabilityRegistry {
    private final Map<String, KnowledgeCapability> capabilities;
    private final List<CapabilityVisibilityPolicy> visibilityPolicies;

    public CapabilityRegistry(List<KnowledgeCapability> capabilityList,
                              List<CapabilityVisibilityPolicy> visibilityPolicies) {
        Map<String, KnowledgeCapability> map = new LinkedHashMap<>();
        if (capabilityList != null) {
            for (KnowledgeCapability capability : capabilityList) {
                String name = capability.definition().name();
                if (map.putIfAbsent(name, capability) != null) throw new IllegalStateException("duplicate capability: " + name);
            }
        }
        this.capabilities = Collections.unmodifiableMap(map);
        this.visibilityPolicies = visibilityPolicies == null ? List.of() : List.copyOf(visibilityPolicies);
    }

    public KnowledgeCapability getVisible(String name, CapabilityInvocationContext context) {
        KnowledgeCapability capability = capabilities.get(name);
        if (capability == null) return null;
        CapabilityDefinition plannerDefinition = capability.plannerDefinition(context);
        return plannerDefinition != null && isVisible(plannerDefinition, context) ? capability : null;
    }

    /** Planner 只能看到当前 scope/domain 下真实可用、并已按上下文收窄后的 Tool Contract。 */
    public List<CapabilityDefinition> listDefinitions(CapabilityInvocationContext context) {
        List<CapabilityDefinition> out = new ArrayList<>();
        for (KnowledgeCapability capability : capabilities.values()) {
            CapabilityDefinition plannerDefinition = capability.plannerDefinition(context);
            if (plannerDefinition != null && isVisible(plannerDefinition, context)) {
                out.add(withExpressionBindingGuidance(plannerDefinition));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 对同时暴露 select + filter 的类型化数据能力补充统一 Planner 契约。
     *
     * <p>这不是领域 intent 规则，而是 relational pipeline 的通用语义：如果用户筛选的是字段派生值，
     * transform 必须出现在 filter value expression 上；如果用户要返回 multi-value 中真正命中的元素，
     * filter/select 必须基于同一 explode source，由执行层做 element binding。这样 Planner 不会把
     * “派生值等于 X”降级成“原始字符串 CONTAINS X”。</p>
     */
    private CapabilityDefinition withExpressionBindingGuidance(CapabilityDefinition definition) {
        Map<String, String> schema = definition.argumentSchema();
        if (schema == null || !schema.containsKey("filter") || !schema.containsKey("select")) return definition;

        Map<String, String> enriched = new LinkedHashMap<>(schema);
        enriched.computeIfPresent("filter", (key, value) -> value
                + " 派生值条件必须把 transforms/explode 写在 filter 的 value expression 自身，operator 比较变换后的值；"
                + "不要只在 select 中变换后再对原字段使用 CONTAINS/STARTS_WITH 近似替代。"
                + " 对 multi-value 的元素级条件，应使用 explode=true。" );
        enriched.computeIfPresent("select", (key, value) -> value
                + " 当目标是返回 multi-value 中真正命中的元素时，select 与 filter 应基于同一 field 且 explode=true；"
                + "select 可返回原始元素，filter 可在同一源元素上使用 transforms，Runtime 会做 element binding，"
                + "不会把同一实体中的其它未命中元素带入结果。" );

        String description = definition.description()
                + " 对派生值过滤执行 transform-before-filter；对同一 explode 多值源执行 element-bound filter/projection。";
        return new CapabilityDefinition(definition.name(), definition.version(), description,
                enriched, definition.requiredArguments(), definition.outputType(), definition.readOnly(),
                definition.requiredPermissions(), definition.supportedDomains(), definition.requiredKbCapabilities(),
                definition.timeoutMs(), definition.maxRows());
    }

    private boolean isVisible(CapabilityDefinition definition, CapabilityInvocationContext context) {
        for (CapabilityVisibilityPolicy policy : visibilityPolicies) {
            if (!policy.isVisible(definition, context)) return false;
        }
        return true;
    }
}
