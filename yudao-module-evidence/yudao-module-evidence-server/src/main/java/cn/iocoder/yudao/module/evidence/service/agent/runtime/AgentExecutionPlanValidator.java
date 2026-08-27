package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plan Schema + DAG + static capability contract validation. No business intent is interpreted here. */
public class AgentExecutionPlanValidator {

    public Validation validate(AgentExecutionPlan plan, AgentExecutionBudget budget) {
        return validate(plan, budget, null, null);
    }

    /**
     * 在 Runtime 真正执行前完成能确定的机器校验。
     *
     * <p>含 $ref 的动态参数必须等上游完成后再由 CapabilityInvoker.prepare 校验；
     * 不含引用的静态节点则在 PLAN_VALIDATION 阶段直接校验 capability 参数契约，避免
     * aggregate/list/object 这类形状错误消耗一次节点执行和后续 replan 预算。</p>
     */
    public Validation validate(AgentExecutionPlan plan,
                               AgentExecutionBudget budget,
                               CapabilityInvoker capabilityInvoker,
                               CapabilityInvocationContext context) {
        if (plan == null) return Validation.invalid("execution plan is null");
        if (StrUtil.isBlank(plan.originalGoal())) return Validation.invalid("originalGoal must not be blank");
        if (plan.replanAttempt() < 0) return Validation.invalid("replanAttempt must be >= 0");
        if (plan.nodes() == null || plan.nodes().isEmpty()) return Validation.invalid("execution plan has no nodes");
        if (budget != null && plan.nodes().size() > budget.maxSteps()) {
            return Validation.invalid("execution plan exceeds maxSteps: " + plan.nodes().size() + " > " + budget.maxSteps());
        }

        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode node : plan.nodes()) {
            if (node == null) return Validation.invalid("execution plan contains null node");
            if (StrUtil.isBlank(node.id())) return Validation.invalid("plan node id must not be blank");
            if (StrUtil.isBlank(node.capability())) return Validation.invalid("plan node capability must not be blank: " + node.id());
            if (byId.putIfAbsent(node.id(), node) != null) return Validation.invalid("duplicate plan node id: " + node.id());
            if (node.dependsOn().contains(node.id())) return Validation.invalid("plan node depends on itself: " + node.id());
        }

        for (PlanNode node : plan.nodes()) {
            for (String dependency : node.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    return Validation.invalid("unknown dependency " + dependency + " for node " + node.id());
                }
            }
            Set<String> references = new HashSet<>();
            String referenceError = collectAndValidateReferences(node.arguments(), references);
            if (referenceError != null) {
                return Validation.invalid(referenceError + " for node " + node.id());
            }
            for (String reference : references) {
                if (!byId.containsKey(reference)) {
                    return Validation.invalid("unknown argument reference " + reference + " for node " + node.id());
                }
                if (!node.dependsOn().contains(reference)) {
                    return Validation.invalid("argument reference must be declared in dependsOn: "
                            + node.id() + " -> " + reference);
                }
            }

            // 只有完全静态的参数才能在执行前做完整 Tool Contract 校验。
            if (capabilityInvoker != null && references.isEmpty()) {
                CapabilityInvoker.PreparedCall prepared = capabilityInvoker.prepare(
                        node.capability(), node.arguments(), context);
                if (!prepared.accepted()) {
                    return Validation.invalid("invalid capability call for node " + node.id() + ": " + prepared.message());
                }
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (PlanNode node : plan.nodes()) {
            if (hasCycle(node.id(), byId, visiting, visited)) {
                return Validation.invalid("execution plan contains dependency cycle");
            }
        }
        return Validation.ok();
    }

    private String collectAndValidateReferences(Object value, Set<String> references) {
        if (value instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref != null) {
                String error = PlanArgumentResolver.validateReference(map);
                if (error != null) return error;
                references.add(String.valueOf(ref));
                return null;
            }
            for (Object nested : map.values()) {
                String error = collectAndValidateReferences(nested, references);
                if (error != null) return error;
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object nested : list) {
                String error = collectAndValidateReferences(nested, references);
                if (error != null) return error;
            }
        }
        return null;
    }

    private boolean hasCycle(String id, Map<String, PlanNode> byId,
                             Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        PlanNode node = byId.get(id);
        if (node != null) {
            for (String dependency : node.dependsOn()) {
                if (hasCycle(dependency, byId, visiting, visited)) return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    public record Validation(boolean valid, String message) {
        public static Validation ok() { return new Validation(true, null); }
        public static Validation invalid(String message) { return new Validation(false, message); }
    }
}
