package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 请求内的语义无进展保护。
 *
 * <p>只有一个计划已经成功执行、但被 Goal Evaluator 判定为 INSUFFICIENT 后才记录。
 * 后续 replan 如果只是换 planId/nodeId/purpose，却仍是同一 capability DAG + 参数语义，
 * 就属于 no-progress；Runtime 不应再次访问相同数据源。</p>
 *
 * <p>本类不保存全局状态，因此不会跨请求污染；也不参与 capability 内部的 transient retry。</p>
 */
public final class NoProgressGuard {

    private final Set<String> insufficientPlanFingerprints = new LinkedHashSet<>();

    public void markInsufficient(AgentExecutionPlan plan) {
        if (plan != null) insufficientPlanFingerprints.add(semanticFingerprint(plan));
    }

    public boolean repeatsInsufficient(AgentExecutionPlan plan) {
        return plan != null && insufficientPlanFingerprints.contains(semanticFingerprint(plan));
    }

    /** package-private for architecture tests. */
    static String semanticFingerprint(AgentExecutionPlan plan) {
        if (plan == null) return "NULL";
        Map<String, String> canonicalNodeIds = new LinkedHashMap<>();
        for (int i = 0; i < plan.nodes().size(); i++) {
            PlanNode node = plan.nodes().get(i);
            canonicalNodeIds.put(node.id(), "n" + i);
        }

        StringBuilder material = new StringBuilder();
        for (int i = 0; i < plan.nodes().size(); i++) {
            PlanNode node = plan.nodes().get(i);
            material.append("node[").append(i).append("]{")
                    .append("capability=").append(node.capability()).append(';')
                    .append("args=").append(normalize(node.arguments(), canonicalNodeIds)).append(';')
                    .append("dependsOn=").append(normalizeDependencies(node.dependsOn(), canonicalNodeIds))
                    .append("};");
        }
        return sha256(material.toString());
    }

    private static String normalizeDependencies(Set<String> dependencies, Map<String, String> nodeIds) {
        if (dependencies == null || dependencies.isEmpty()) return "[]";
        List<String> normalized = new ArrayList<>();
        for (String dependency : dependencies) normalized.add(nodeIds.getOrDefault(dependency, dependency));
        normalized.sort(Comparator.naturalOrder());
        return normalized.toString();
    }

    private static String normalize(Object value, Map<String, String> nodeIds) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> source) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(entry.getKey()).append('=');
                if ("$ref".equals(entry.getKey()) && entry.getValue() != null) {
                    String raw = String.valueOf(entry.getValue());
                    out.append(nodeIds.getOrDefault(raw, raw));
                } else {
                    out.append(normalize(entry.getValue(), nodeIds));
                }
            }
            return out.append('}').toString();
        }
        if (value instanceof Set<?> set) {
            List<String> items = set.stream().map(item -> normalize(item, nodeIds)).sorted().toList();
            return items.toString();
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            for (Object item : collection) items.add(normalize(item, nodeIds));
            return items.toString();
        }
        if (value.getClass().isArray()) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) items.add(normalize(Array.get(value, i), nodeIds));
            return items.toString();
        }
        if (value instanceof Number number) return number.toString();
        if (value instanceof Boolean bool) return bool.toString();
        return String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
