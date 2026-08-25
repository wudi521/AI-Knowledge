package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用实体集合运算能力。用于 DAG 中把多个已验证/候选实体集合做交集、并集或差集。
 *
 * <p>它只对 ID 集合做确定性运算，却无法证明输入 ID 本身的业务真实性，因此输出统一保持
 * candidateEntityIds，不自动升级为 verifiedEntityIds。可信升级只能由真正的结构化/关系事实 Tool 完成。</p>
 */
@Component
public class EntitySetOperationCapability implements KnowledgeCapability {
    public static final String NAME = "entity_set_operation";
    private static final int MAX_INPUT_SETS = 8;
    private static final int MAX_IDS_PER_SET = 500;
    private static final int MAX_OUTPUT_IDS = 500;

    @Override
    public CapabilityDefinition definition() {
        return new CapabilityDefinition(NAME, "2",
                "对上游 PlanNode 产生的实体 ID 集合做通用集合运算。支持 INTERSECT/UNION/DIFFERENCE。"
                        + "结构化 Tool 可引用 verifiedEntityIds；语义/全文检索必须引用 candidateEntityIds。"
                        + "本 Tool 输出仍是 candidateEntityIds，不自动提升可信级别。",
                Map.of(
                        "operation", "必填。INTERSECT / UNION / DIFFERENCE。",
                        "sets", "必填。2~8 个实体 ID 数组。按上游事实类型使用 DAG $ref selector=verifiedEntityIds 或 candidateEntityIds。"
                ),
                Set.of("operation", "sets"), "CANDIDATE_ENTITY_ID_SET", true,
                Set.of(), Set.of(), Set.of(), 1_000L, MAX_OUTPUT_IDS);
    }

    @Override
    public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                           Map<String, Object> arguments) {
        Operation operation = operation(arguments == null ? null : arguments.get("operation"));
        if (operation == null) {
            return CapabilityArgumentValidation.invalid("operation must be INTERSECT, UNION or DIFFERENCE");
        }
        Object rawSets = arguments == null ? null : arguments.get("sets");
        if (!(rawSets instanceof Collection<?> collection)
                || collection.size() < 2 || collection.size() > MAX_INPUT_SETS) {
            return CapabilityArgumentValidation.invalid("sets must contain 2..8 entity-id arrays");
        }
        for (Object rawSet : collection) {
            if (!(rawSet instanceof Collection<?> ids) || ids.size() > MAX_IDS_PER_SET) {
                return CapabilityArgumentValidation.invalid("each set must be an entity-id array with at most 500 ids");
            }
            for (Object rawId : ids) {
                if (entityId(rawId) == null) {
                    return CapabilityArgumentValidation.invalid("entity ids must be positive integers");
                }
            }
        }
        return CapabilityArgumentValidation.ok();
    }

    @Override
    public String canonicalExecutionKey(CapabilityInvocationContext context, Map<String, Object> arguments) {
        Operation operation = operation(arguments == null ? null : arguments.get("operation"));
        List<List<Long>> sets = sets(arguments == null ? null : arguments.get("sets"));
        if (operation == null || sets.size() < 2) return null;
        List<List<Long>> normalized = sets.stream()
                .map(set -> set.stream().distinct().sorted().toList())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (operation == Operation.INTERSECT || operation == Operation.UNION) {
            normalized.sort(Comparator.comparing(Object::toString));
        } else if (normalized.size() > 2) {
            List<List<Long>> tail = new ArrayList<>(normalized.subList(1, normalized.size()));
            tail.sort(Comparator.comparing(Object::toString));
            List<List<Long>> ordered = new ArrayList<>();
            ordered.add(normalized.get(0));
            ordered.addAll(tail);
            normalized = ordered;
        }
        return "operation=" + operation + ";sets=" + normalized;
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        Operation operation = operation(arguments == null ? null : arguments.get("operation"));
        List<List<Long>> sets = sets(arguments == null ? null : arguments.get("sets"));
        if (operation == null || sets.size() < 2) {
            return CapabilityResult.recoverableFailure(
                    "entity set operation requires a valid operation and at least two sets",
                    Map.of("errorKind", "SET_OPERATION_CONTRACT"));
        }

        LinkedHashSet<Long> result = new LinkedHashSet<>(sets.get(0));
        switch (operation) {
            case INTERSECT -> {
                for (int i = 1; i < sets.size(); i++) result.retainAll(new LinkedHashSet<>(sets.get(i)));
            }
            case UNION -> {
                for (int i = 1; i < sets.size(); i++) result.addAll(sets.get(i));
            }
            case DIFFERENCE -> {
                for (int i = 1; i < sets.size(); i++) result.removeAll(new LinkedHashSet<>(sets.get(i)));
            }
        }
        List<Long> ids = result.stream().limit(MAX_OUTPUT_IDS).toList();
        boolean truncated = result.size() > MAX_OUTPUT_IDS;
        Output output = new Output(operation.name(), ids, sets.size(), truncated);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("outputCount", ids.size());
        metadata.put("inputSetCount", sets.size());
        metadata.put("operation", operation.name());
        metadata.put("entityTrust", "CANDIDATE");
        metadata.put("truncated", truncated);
        // 集合运算只对输入集合本身是确定性的；它不知道上游候选集合是否覆盖全集。
        metadata.put("completeDataset", false);
        metadata.put("authoritativeEmpty", false);
        metadata.put("outputComplete", !truncated);
        if (truncated) {
            return CapabilityResult.partial(output,
                    "entity set operation output was truncated at " + MAX_OUTPUT_IDS, metadata);
        }
        if (ids.isEmpty()) {
            return CapabilityResult.empty(output, "entity set operation produced an empty candidate set", metadata);
        }
        return CapabilityResult.success(output, metadata);
    }

    private Operation operation(Object raw) {
        if (raw == null) return null;
        try {
            return Operation.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private List<List<Long>> sets(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return List.of();
        List<List<Long>> out = new ArrayList<>();
        for (Object rawSet : collection) {
            if (!(rawSet instanceof Collection<?> ids)) return List.of();
            LinkedHashSet<Long> normalized = new LinkedHashSet<>();
            for (Object rawId : ids) {
                Long id = entityId(rawId);
                if (id == null) return List.of();
                normalized.add(id);
            }
            out.add(List.copyOf(normalized));
        }
        return List.copyOf(out);
    }

    private Long entityId(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) return null;
        try {
            long value = Long.parseLong(String.valueOf(raw).trim());
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private enum Operation {
        INTERSECT,
        UNION,
        DIFFERENCE
    }

    public record Output(String operation,
                         List<Long> candidateEntityIds,
                         int inputSetCount,
                         boolean truncated) implements AgentCapabilityOutput {
        public Output {
            candidateEntityIds = candidateEntityIds == null ? List.of() : List.copyOf(candidateEntityIds);
        }

        @Override
        public String summary() {
            return "entity set " + operation + " produced " + candidateEntityIds.size()
                    + " candidate id(s) from " + inputSetCount + " input set(s)"
                    + (truncated ? "; truncated" : "");
        }

        @Override
        public String progressHash() {
            return operation + ":CANDIDATE:" + candidateEntityIds;
        }
    }
}
