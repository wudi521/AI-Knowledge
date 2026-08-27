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

            // entityIds 是平台内部实体主键，不是用户业务字段。Planner 绝不能凭空生成字面 ID；
            // 它只能消费上游 Tool 明确输出的 candidateEntityIds/verifiedEntityIds，再由下游确定性 Tool 重新验证。
            String entityScopeError = validateEntityIdScopeReference(node.arguments().get("entityIds"));
            if (entityScopeError != null) {
                return Validation.invalid(entityScopeError + " for node " + node.id());
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

            // 当前 Agent DAG 的 dependsOn 定义为“数据依赖”，不是单纯的执行顺序提示。
            // 如果声明依赖却没有通过 $ref 消费上游结果，下游查询范围不会自动继承上游，
            // 容易出现“目的写的是这两个对象，实际却对全库执行”的伪 DAG。此类计划必须在执行前拒绝。
            if (!node.dependsOn().isEmpty()) {
                Set<String> unconsumedDependencies = new HashSet<>(node.dependsOn());
                unconsumedDependencies.removeAll(references);
                if (!unconsumedDependencies.isEmpty()) {
                    return Validation.invalid("declared dependency must be consumed by explicit argument $ref: "
                            + node.id() + " -> " + unconsumedDependencies);
                }
            }

            // 只有完全静态的参数才能在执行前做完整 Tool Contract 校验。
            if (capabilityInvoker != null && references.isEmpty()) {
                CapabilityInvoker.PreparedCall prepared = capabilityInvoker.prepare(
                        node.capability(), node.arguments(), context);
                if (!prepared.accepted()) {
                    return Validation.invalid("invalid capability call for node " + node.id() + ": " + prepared.message());
                }
                String nestedContractError = validateDeclaredNestedContracts(prepared, node.arguments());
                if (nestedContractError != null) {
                    return Validation.invalid("invalid capability call for node " + node.id() + ": " + nestedContractError);
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

    /**
     * Internal entity IDs must preserve machine provenance. A single typed reference keeps the boundary explicit;
     * multi-source sets should first be composed by entity_set_operation, then referenced here.
     */
    private String validateEntityIdScopeReference(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map) || !map.containsKey("$ref")) {
            return "entityIds must come from an explicit upstream $ref; literal internal entity ids are forbidden";
        }
        String selector = map.get("selector") == null ? "" : String.valueOf(map.get("selector")).trim();
        if (!"candidateEntityIds".equals(selector) && !"verifiedEntityIds".equals(selector)) {
            return "entityIds reference selector must be candidateEntityIds or verifiedEntityIds";
        }
        if (!Boolean.TRUE.equals(map.get("required"))) {
            return "entityIds reference must set required=true";
        }
        String expect = map.get("expect") == null ? "" : String.valueOf(map.get("expect")).trim().toUpperCase();
        if (!"LIST".equals(expect)) {
            return "entityIds reference must set expect=LIST";
        }
        if (map.get("path") != null) {
            return "entityIds reference must not use path projection";
        }
        return null;
    }

    /**
     * argumentSchema 是 capability 对 Planner 暴露的机器契约。这里仅对已经声明的标准 Query-IR
     * 嵌套参数补做静态细节校验，不解释 originalGoal，也不绑定任何领域字段或业务 intent。
     */
    private String validateDeclaredNestedContracts(CapabilityInvoker.PreparedCall prepared,
                                                   Map<String, Object> arguments) {
        if (prepared == null || prepared.capability() == null || arguments == null || arguments.isEmpty()) return null;
        Map<String, String> schema = prepared.capability().definition().argumentSchema();
        if (schema == null || schema.isEmpty()) return null;

        if (schema.containsKey("orderBy")) {
            String error = validateOrderBy(arguments.get("orderBy"));
            if (error != null) return error;
        }
        if (schema.containsKey("having")) {
            String error = validateHaving(arguments.get("having"));
            if (error != null) return error;
        }
        return null;
    }

    private String validateOrderBy(Object raw) {
        if (raw == null) return null;
        List<?> items = raw instanceof List<?> list ? list : List.of(raw);
        if (items.isEmpty()) return "orderBy must not be empty when provided";
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) return "orderBy item must be an object";

            int sources = 0;
            if (Boolean.TRUE.equals(map.get("aggregateValue"))) sources++;
            if (nonBlank(map.get("metric"))) sources++;
            if (map.get("value") != null) sources++;
            else if (nonBlank(map.get("field")) || nonBlank(map.get("code"))) sources++;

            if (sources == 0) return "order-by source is missing";
            if (sources > 1) return "orderBy item must declare exactly one source";

            Object direction = map.containsKey("direction") ? map.get("direction") : map.get("sort");
            if (direction != null) {
                String value = String.valueOf(direction).trim().toUpperCase();
                if (!"ASC".equals(value) && !"DESC".equals(value)) {
                    return "orderBy direction must be ASC or DESC";
                }
            }
        }
        return null;
    }

    private String validateHaving(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) return "having must be an object";
        String operator = map.get("operator") == null ? "" : String.valueOf(map.get("operator")).trim().toUpperCase();
        Set<String> supported = Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN");
        if (!supported.contains(operator)) return "having.operator is required or invalid";

        Object rawValues = map.containsKey("values") ? map.get("values") : map.get("value");
        List<?> values;
        if (rawValues instanceof List<?> list) values = list;
        else if (rawValues == null) values = List.of();
        else values = List.of(rawValues);

        if (values.isEmpty()) return "having values are required";
        if ("BETWEEN".equals(operator) && values.size() != 2) return "having BETWEEN requires exactly two values";
        if (!"IN".equals(operator) && !"BETWEEN".equals(operator) && values.size() != 1) {
            return "having " + operator + " requires exactly one value";
        }
        for (Object value : values) {
            if (!finiteNumber(value)) return "having values must be finite numbers";
        }
        return null;
    }

    private boolean nonBlank(Object value) {
        return value != null && StrUtil.isNotBlank(String.valueOf(value));
    }

    private boolean finiteNumber(Object value) {
        if (value instanceof Number number) return Double.isFinite(number.doubleValue());
        if (value == null) return false;
        try {
            return Double.isFinite(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return false;
        }
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
