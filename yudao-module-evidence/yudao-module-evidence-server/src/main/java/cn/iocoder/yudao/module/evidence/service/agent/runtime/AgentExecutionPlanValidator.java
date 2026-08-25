package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Plan Schema + DAG validation. No business intent is interpreted here. */
public class AgentExecutionPlanValidator {

    public Validation validate(AgentExecutionPlan plan, AgentExecutionBudget budget) {
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
