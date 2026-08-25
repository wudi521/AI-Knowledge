package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves explicit DAG data references in node arguments.
 *
 * <p>Reference shape: {"$ref":"node-a","selector":"verifiedEntityIds"}.
 * Supported selectors are deliberately finite: data, metadata, status, candidateEntityIds,
 * verifiedEntityIds, deterministicAnswer, evidences and summary. No expression language or
 * arbitrary reflection is allowed.</p>
 */
public class PlanArgumentResolver {

    public Map<String, Object> resolve(Map<String, Object> arguments,
                                       Map<String, CapabilityResult> completedResults) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (arguments == null) return out;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            out.put(entry.getKey(), resolveValue(entry.getValue(), completedResults));
        }
        return out;
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
        String nodeId = String.valueOf(ref.get("$ref"));
        CapabilityResult result = completedResults == null ? null : completedResults.get(nodeId);
        if (result == null) throw new IllegalArgumentException("referenced node has not completed: " + nodeId);
        if (!result.success()) throw new IllegalArgumentException("referenced node did not produce a usable result: " + nodeId);
        String selector = ref.get("selector") == null ? "data" : String.valueOf(ref.get("selector"));
        return switch (selector) {
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
    }

    private AgentCapabilityOutput output(CapabilityResult result) {
        if (result.data() instanceof AgentCapabilityOutput output) return output;
        throw new IllegalArgumentException("referenced capability output does not implement AgentCapabilityOutput");
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }
}
