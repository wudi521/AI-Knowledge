package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对 structured_query 的原始过滤参数做领域无关的可满足性检查。
 *
 * <p>这里只拒绝能够在执行前确定为矛盾的条件，例如单值字段在同一个 AND 分支里同时要求
 * 等于两个互斥值。多值字段不会套用该规则，因为“成员包含 A 且包含 B”本身可以成立。</p>
 */
final class StructuredFilterArgumentValidator {

    private StructuredFilterArgumentValidator() {
    }

    static String validate(DomainFieldRegistry fieldRegistry, String domainCode, Object rawFilter) {
        if (fieldRegistry == null || StrUtil.isBlank(domainCode) || rawFilter == null) return null;
        return validateNode(fieldRegistry, domainCode, rawFilter);
    }

    private static String validateNode(DomainFieldRegistry fieldRegistry, String domainCode, Object raw) {
        Map<String, Object> map = map(raw);
        if (map.isEmpty()) return null;
        String logic = text(map.get("logic")).toUpperCase(Locale.ROOT);
        if ("AND".equals(logic) || "OR".equals(logic)) {
            List<Object> children = objectList(firstNonNull(map.get("children"), map.get("conditions")));
            for (Object child : children) {
                String nested = validateNode(fieldRegistry, domainCode, child);
                if (nested != null) return nested;
            }
            if ("AND".equals(logic)) {
                return validateConjunction(fieldRegistry, domainCode, raw);
            }
        }
        return null;
    }

    private static String validateConjunction(DomainFieldRegistry fieldRegistry, String domainCode, Object raw) {
        List<SetConstraint> constraints = new ArrayList<>();
        collectConjunctiveConstraints(fieldRegistry, domainCode, raw, constraints);
        Map<String, LinkedHashSet<String>> allowedByExpression = new LinkedHashMap<>();
        Map<String, String> fieldByExpression = new LinkedHashMap<>();
        for (SetConstraint constraint : constraints) {
            LinkedHashSet<String> current = allowedByExpression.get(constraint.expressionKey());
            if (current == null) {
                allowedByExpression.put(constraint.expressionKey(), new LinkedHashSet<>(constraint.allowedValues()));
                fieldByExpression.put(constraint.expressionKey(), constraint.fieldCode());
                continue;
            }
            current.retainAll(constraint.allowedValues());
            if (current.isEmpty()) {
                String field = fieldByExpression.getOrDefault(constraint.expressionKey(), constraint.fieldCode());
                return "contradictory filter on single-valued field " + field
                        + ": AND alternatives have no common value; use IN or OR for alternative values";
            }
        }
        return null;
    }

    private static void collectConjunctiveConstraints(DomainFieldRegistry fieldRegistry,
                                                      String domainCode,
                                                      Object raw,
                                                      List<SetConstraint> out) {
        Map<String, Object> map = map(raw);
        if (map.isEmpty()) return;
        String logic = text(map.get("logic")).toUpperCase(Locale.ROOT);
        if ("AND".equals(logic)) {
            for (Object child : objectList(firstNonNull(map.get("children"), map.get("conditions")))) {
                collectConjunctiveConstraints(fieldRegistry, domainCode, child, out);
            }
            return;
        }
        if ("OR".equals(logic)) return;

        String rawField = text(firstNonNull(map.get("field"), map.get("code")));
        FieldDefinition field = resolveField(fieldRegistry, domainCode, rawField);
        if (field == null || field.isMultiValue()) return;

        String operator = text(map.get("operator")).toUpperCase(Locale.ROOT);
        if (StrUtil.isBlank(operator)) operator = "EQ";
        if (!"EQ".equals(operator) && !"IN".equals(operator)) return;

        Set<String> allowed = scalarValues(firstNonNull(map.get("values"), map.get("value")));
        if (allowed == null || allowed.isEmpty()) return;
        String expressionKey = field.getFieldCode() + "|" + transformKey(map.get("transforms"));
        out.add(new SetConstraint(expressionKey, field.getFieldCode(), allowed));
    }

    private static FieldDefinition resolveField(DomainFieldRegistry registry, String domainCode, String raw) {
        if (StrUtil.isBlank(raw)) return null;
        String normalized = raw.trim();
        return registry.byCode(domainCode, normalized.toUpperCase(Locale.ROOT))
                .or(() -> registry.findByAlias(normalized, domainCode)).orElse(null);
    }

    /** null 表示值仍是动态引用/复杂对象，当前阶段不能安全判断互斥。 */
    private static Set<String> scalarValues(Object raw) {
        if (raw == null) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (!isScalar(item)) return null;
                String value = text(item);
                if (StrUtil.isNotBlank(value)) out.add(value);
            }
            return out;
        }
        if (!isScalar(raw)) return null;
        String value = text(raw);
        if (StrUtil.isNotBlank(value)) out.add(value);
        return out;
    }

    private static boolean isScalar(Object raw) {
        return raw instanceof String || raw instanceof Number || raw instanceof Boolean;
    }

    private static String transformKey(Object raw) {
        if (raw == null) return "";
        List<String> values = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) values.add(text(item).toUpperCase(Locale.ROOT));
        } else {
            values.add(text(raw).toUpperCase(Locale.ROOT));
        }
        return String.join(",", values);
    }

    private static List<Object> objectList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) out.add(item);
            return List.copyOf(out);
        }
        return List.of(raw);
    }

    private static Map<String, Object> map(Object raw) {
        if (raw instanceof JSONObject json) {
            Map<String, Object> out = new LinkedHashMap<>();
            json.forEach(out::put);
            return out;
        }
        if (raw instanceof Map<?, ?> source) {
            Map<String, Object> out = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key != null) out.put(String.valueOf(key), value);
            });
            return out;
        }
        return Map.of();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private record SetConstraint(String expressionKey, String fieldCode, Set<String> allowedValues) {
    }
}
