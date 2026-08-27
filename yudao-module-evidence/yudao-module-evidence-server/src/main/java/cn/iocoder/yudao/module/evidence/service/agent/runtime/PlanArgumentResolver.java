package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves explicit DAG data references in node arguments.
 *
 * <p>Base reference shape: {"$ref":"node-a","selector":"verifiedEntityIds"}.</p>
 * <p>Typed dataflow projection shape:
 * {"$ref":"node-a","selector":"data","path":"rows[*].groupKey",
 *  "distinct":true,"required":true,"expect":"LIST"}.</p>
 *
 * <p>References into metadata.dataflowRows fail closed when the producer reports an incomplete output.
 * A plan may opt into partial consumption only with an explicit {@code allowPartial:true}; this keeps
 * accidental default/result limits from silently becoming an incomplete downstream scope.</p>
 *
 * <p>The resolver intentionally supports only maps, lists/arrays and Java record components.
 * It is not an expression language and never invokes arbitrary bean getters or methods.</p>
 */
public class PlanArgumentResolver {
    private static final Set<String> SUPPORTED_SELECTORS = Set.of(
            "data", "metadata", "status", "candidateEntityIds", "verifiedEntityIds",
            "deterministicAnswer", "evidences", "summary"
    );
    private static final Set<String> SUPPORTED_EXPECTATIONS = Set.of("ANY", "LIST", "MAP", "SCALAR");
    private static final Set<String> SUPPORTED_REFERENCE_KEYS = Set.of(
            "$ref", "selector", "path", "distinct", "required", "expect", "allowPartial"
    );
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_|:-]*(?:\\[\\*])?");

    public Map<String, Object> resolve(Map<String, Object> arguments,
                                       Map<String, CapabilityResult> completedResults) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (arguments == null) return out;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            out.put(entry.getKey(), resolveValue(entry.getValue(), completedResults));
        }
        return out;
    }

    /** Returns null when valid, otherwise a stable plan-contract error message. */
    public static String validateReference(Map<?, ?> rawRef) {
        if (rawRef == null || !rawRef.containsKey("$ref")) return null;
        Map<String, Object> ref = stringMapStatic(rawRef);
        String nodeId = ref.get("$ref") == null ? null : String.valueOf(ref.get("$ref"));
        if (StrUtil.isBlank(nodeId)) return "plan reference $ref must not be blank";
        for (String key : ref.keySet()) {
            if (!SUPPORTED_REFERENCE_KEYS.contains(key)) {
                return "unsupported plan reference property: " + key;
            }
        }
        String selector = ref.get("selector") == null ? "data" : String.valueOf(ref.get("selector"));
        if (!SUPPORTED_SELECTORS.contains(selector)) {
            return "unsupported plan reference selector: " + selector;
        }
        Object pathRaw = ref.get("path");
        if (pathRaw != null) {
            if (!(pathRaw instanceof String path) || !validPath(path)) {
                return "invalid plan reference path: " + pathRaw;
            }
        }
        if (ref.containsKey("distinct") && !(ref.get("distinct") instanceof Boolean)) {
            return "plan reference distinct must be boolean";
        }
        if (ref.containsKey("required") && !(ref.get("required") instanceof Boolean)) {
            return "plan reference required must be boolean";
        }
        if (ref.containsKey("allowPartial") && !(ref.get("allowPartial") instanceof Boolean)) {
            return "plan reference allowPartial must be boolean";
        }
        if (ref.containsKey("expect")) {
            String expect = String.valueOf(ref.get("expect")).trim().toUpperCase();
            if (!SUPPORTED_EXPECTATIONS.contains(expect)) {
                return "unsupported plan reference expect: " + ref.get("expect");
            }
        }
        return null;
    }

    private Object resolveValue(Object value, Map<String, CapabilityResult> completedResults) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = stringMap(rawMap);
            if (map.containsKey("$ref")) return resolveReference(map, completedResults);
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                nested.put(entry.getKey(), resolveValue(entry.getValue(), completedResults));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(resolveValue(item, completedResults));
            return out;
        }
        return value;
    }

    private Object resolveReference(Map<String, Object> ref, Map<String, CapabilityResult> completedResults) {
        String contractError = validateReference(ref);
        if (contractError != null) throw new IllegalArgumentException(contractError);

        String nodeId = String.valueOf(ref.get("$ref"));
        CapabilityResult result = completedResults == null ? null : completedResults.get(nodeId);
        if (result == null) throw new IllegalArgumentException("referenced node has not completed: " + nodeId);
        if (!result.success()) throw new IllegalArgumentException("referenced node did not produce a usable result: " + nodeId);

        String selector = ref.get("selector") == null ? "data" : String.valueOf(ref.get("selector"));
        String path = ref.get("path") instanceof String value ? value.trim() : "";
        rejectIncompleteDataflow(result, ref, selector, path, nodeId);

        Object selected = switch (selector) {
            case "data" -> result.data();
            case "metadata" -> result.metadata();
            case "status" -> result.status().name();
            case "candidateEntityIds" -> output(result).candidateEntityIds();
            case "verifiedEntityIds" -> output(result).verifiedEntityIds();
            case "deterministicAnswer" -> output(result).deterministicAnswer();
            case "evidences" -> output(result).evidences();
            case "summary" -> output(result).summary();
            default -> throw new IllegalArgumentException("unsupported plan reference selector: " + selector);
        };

        if (StrUtil.isNotBlank(path)) selected = project(selected, path);
        if (Boolean.TRUE.equals(ref.get("distinct"))) selected = distinct(selected);
        if (Boolean.TRUE.equals(ref.get("required")) && empty(selected)) {
            throw new IllegalArgumentException("required plan reference resolved empty: " + nodeId
                    + (StrUtil.isBlank(path) ? "" : "." + path));
        }
        String expect = ref.get("expect") == null ? "ANY" : String.valueOf(ref.get("expect")).trim().toUpperCase();
        validateExpectedType(selected, expect, nodeId, path);
        return selected;
    }

    private void rejectIncompleteDataflow(CapabilityResult result,
                                          Map<String, Object> ref,
                                          String selector,
                                          String path,
                                          String nodeId) {
        if (!"metadata".equals(selector) || !dataflowPath(path) || Boolean.TRUE.equals(ref.get("allowPartial"))) {
            return;
        }
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        if (!Boolean.TRUE.equals(metadata.get("outputComplete"))) {
            throw new IllegalArgumentException("referenced dataflow output is incomplete: " + nodeId + "." + path
                    + "; set allowPartial=true only when partial consumption is intentional");
        }
    }

    private boolean dataflowPath(String path) {
        return "dataflowRows".equals(path) || path.startsWith("dataflowRows.") || path.startsWith("dataflowRows[*]");
    }

    private Object project(Object root, String path) {
        if (!validPath(path)) throw new IllegalArgumentException("invalid plan reference path: " + path);
        List<Object> current = new ArrayList<>();
        current.add(root);
        boolean collectionProjection = false;

        for (String rawSegment : path.split("\\.")) {
            boolean wildcard = rawSegment.endsWith("[*]");
            String segment = wildcard ? rawSegment.substring(0, rawSegment.length() - 3) : rawSegment;
            List<Object> next = new ArrayList<>();
            for (Object value : current) {
                Object child = readComponent(value, segment, path);
                if (wildcard) {
                    collectionProjection = true;
                    appendElements(child, next, path);
                } else if (child != null) {
                    next.add(child);
                }
            }
            current = next;
            if (current.isEmpty()) break;
        }
        if (collectionProjection || current.size() != 1) return Collections.unmodifiableList(current);
        return current.isEmpty() ? null : current.get(0);
    }

    private Object readComponent(Object value, String key, String fullPath) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("plan reference path not found: " + fullPath + " (missing " + key + ")");
            }
            return map.get(key);
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (!component.getName().equals(key)) continue;
                try {
                    return component.getAccessor().invoke(value);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalArgumentException("cannot read plan reference record component: " + key);
                }
            }
            throw new IllegalArgumentException("plan reference path not found: " + fullPath + " (missing " + key + ")");
        }
        throw new IllegalArgumentException("plan reference path can only traverse map/record values: " + fullPath);
    }

    private void appendElements(Object value, List<Object> out, String fullPath) {
        if (value == null) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null) out.add(item);
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(value, i);
                if (item != null) out.add(item);
            }
            return;
        }
        throw new IllegalArgumentException("plan reference wildcard requires a collection/array: " + fullPath);
    }

    private Object distinct(Object value) {
        if (value instanceof Collection<?> collection) {
            return List.copyOf(new LinkedHashSet<>(collection));
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            appendElements(value, values, "distinct");
            return List.copyOf(new LinkedHashSet<>(values));
        }
        return value;
    }

    private boolean empty(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence text) return text.toString().isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return value.getClass().isArray() && Array.getLength(value) == 0;
    }

    private void validateExpectedType(Object value, String expect, String nodeId, String path) {
        boolean valid = switch (expect) {
            case "ANY" -> true;
            case "LIST" -> value instanceof Iterable<?> || (value != null && value.getClass().isArray());
            case "MAP" -> value instanceof Map<?, ?>;
            case "SCALAR" -> value == null || (!(value instanceof Iterable<?>) && !(value instanceof Map<?, ?>)
                    && !value.getClass().isArray());
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("plan reference type mismatch: expected " + expect + " for " + nodeId
                    + (StrUtil.isBlank(path) ? "" : "." + path));
        }
    }

    private AgentCapabilityOutput output(CapabilityResult result) {
        if (result.data() instanceof AgentCapabilityOutput output) return output;
        throw new IllegalArgumentException("referenced capability output does not implement AgentCapabilityOutput");
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        return stringMapStatic(raw);
    }

    private static Map<String, Object> stringMapStatic(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static boolean validPath(String path) {
        if (StrUtil.isBlank(path) || path.length() > 240) return false;
        String[] segments = path.split("\\.", -1);
        if (segments.length == 0 || segments.length > 12) return false;
        for (String segment : segments) {
            if (!PATH_SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }
}
