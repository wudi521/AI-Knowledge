package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correlates deterministic structured references inside one execution plan.
 *
 * <p>A grouped/extrema node may prove only {@code groupKey + aggregateValue}; a dependent ROWS node
 * may then materialize the requested fields for those keys. Returning both blocks independently is
 * factually correct but reads like execution logs. This composer joins them by machine keys and keeps
 * the original references/provenance identities unchanged.</p>
 *
 * <p>No business intent is interpreted here. Correlation is allowed only when the detail node actually
 * depends on and references the grouped node, and a detail row exposes an alias equal to the group key.
 * If the join cannot be proven, the original deterministic answers are left untouched.</p>
 */
final class StructuredReferenceAnswerComposer {

    private StructuredReferenceAnswerComposer() {
    }

    static List<ReferenceRecord> compose(AgentExecutionPlan plan, List<ReferenceRecord> references) {
        if (plan == null || references == null || references.isEmpty()) return references == null ? List.of() : List.copyOf(references);

        Map<String, PlanNode> nodes = new LinkedHashMap<>();
        for (PlanNode node : plan.nodes()) {
            if (node != null && StrUtil.isNotBlank(node.id())) nodes.put(node.id(), node);
        }

        Map<String, GroupFact> groupFacts = new LinkedHashMap<>();
        for (ReferenceRecord reference : references) {
            GroupFact fact = groupFact(reference);
            if (fact != null) groupFacts.put(reference.nodeId(), fact);
        }
        if (groupFacts.isEmpty()) return List.copyOf(references);

        Map<String, String> replacementAnswers = new LinkedHashMap<>();
        Set<String> suppressAnswers = new LinkedHashSet<>();

        for (ReferenceRecord detailReference : references) {
            if (!isStructuredShape(detailReference, "ROWS")) continue;
            PlanNode detailNode = nodes.get(detailReference.nodeId());
            if (detailNode == null || detailNode.dependsOn().isEmpty()) continue;

            Set<String> referencedNodes = referencedNodeIds(detailNode.arguments());
            List<GroupFact> eligibleGroups = new ArrayList<>();
            for (String dependency : detailNode.dependsOn()) {
                GroupFact group = groupFacts.get(dependency);
                if (group != null && referencedNodes.contains(dependency)) eligibleGroups.add(group);
            }
            if (eligibleGroups.isEmpty()) continue;

            List<DetailEntity> detailEntities = detailEntities(detailReference);
            if (detailEntities.isEmpty()) continue;

            Map<String, DetailEntity> matchedByGroupNode = new LinkedHashMap<>();
            boolean allDetailsMatched = true;
            for (DetailEntity detail : detailEntities) {
                boolean matched = false;
                for (GroupFact group : eligibleGroups) {
                    if (detail.aliases().contains(group.groupKey())) {
                        matchedByGroupNode.put(group.reference().nodeId(), detail);
                        matched = true;
                    }
                }
                if (!matched) allDetailsMatched = false;
            }

            if (matchedByGroupNode.isEmpty()) continue;
            for (GroupFact group : eligibleGroups) {
                DetailEntity detail = matchedByGroupNode.get(group.reference().nodeId());
                if (detail == null) continue;
                String detailText = detailText(detailReference.deterministicAnswer(), detail.entityName(), group.groupKey());
                if (StrUtil.isBlank(detailText)) continue;
                replacementAnswers.put(group.reference().nodeId(), appendDetail(group.reference().deterministicAnswer(), detailText));
            }

            boolean everyEligibleGroupMatched = eligibleGroups.stream()
                    .allMatch(group -> matchedByGroupNode.containsKey(group.reference().nodeId()));
            if (allDetailsMatched && everyEligibleGroupMatched) suppressAnswers.add(detailReference.nodeId());
        }

        if (replacementAnswers.isEmpty()) return List.copyOf(references);
        List<ReferenceRecord> out = new ArrayList<>(references.size());
        for (ReferenceRecord reference : references) {
            String answer = replacementAnswers.getOrDefault(reference.nodeId(), reference.deterministicAnswer());
            if (suppressAnswers.contains(reference.nodeId())) answer = null;
            out.add(copy(reference, answer));
        }
        return List.copyOf(out);
    }

    private static GroupFact groupFact(ReferenceRecord reference) {
        if (!isStructuredShape(reference, "GROUP")) return null;
        List<Map<String, Object>> rows = dataflowRows(reference);
        if (rows.size() != 1) return null;
        Object groupKey = rows.get(0).get("groupKey");
        if (groupKey == null || StrUtil.isBlank(String.valueOf(groupKey))) return null;
        return new GroupFact(reference, String.valueOf(groupKey));
    }

    private static List<DetailEntity> detailEntities(ReferenceRecord reference) {
        Map<String, MutableDetail> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : dataflowRows(reference)) {
            String entityName = text(row.get("entityName"));
            if (StrUtil.isBlank(entityName)) continue;
            MutableDetail detail = grouped.computeIfAbsent(entityName, ignored -> new MutableDetail(entityName));
            detail.aliases.add(entityName);
            Object fieldsRaw = row.get("fields");
            if (fieldsRaw instanceof Map<?, ?> fields) {
                for (Object value : fields.values()) {
                    if (value == null) continue;
                    String scalar = text(value);
                    if (StrUtil.isNotBlank(scalar)) detail.aliases.add(scalar);
                }
            }
        }
        return grouped.values().stream()
                .map(value -> new DetailEntity(value.entityName, Set.copyOf(value.aliases)))
                .toList();
    }

    private static String detailText(String presentedAnswer, String entityName, String joinKey) {
        if (StrUtil.isBlank(presentedAnswer) || StrUtil.isBlank(entityName)) return null;
        for (String rawLine : presentedAnswer.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = stripListPrefix(rawLine);
            if (!line.startsWith(entityName)) continue;
            if (line.length() == entityName.length()) return null;
            int separator = line.indexOf('：', entityName.length());
            if (separator < 0 || separator >= line.length() - 1) return null;
            String detail = line.substring(separator + 1).trim();
            List<String> kept = new ArrayList<>();
            for (String pair : detail.split("；")) {
                String trimmed = pair.trim();
                int equals = trimmed.indexOf('=');
                if (equals > 0 && joinKey.equals(trimmed.substring(equals + 1).trim())) continue;
                if (StrUtil.isNotBlank(trimmed)) kept.add(trimmed);
            }
            return String.join("；", kept);
        }
        return null;
    }

    private static String appendDetail(String groupAnswer, String detail) {
        if (StrUtil.isBlank(groupAnswer)) return detail;
        if (StrUtil.isBlank(detail) || groupAnswer.contains(detail)) return groupAnswer;
        return groupAnswer.trim() + "\n" + detail;
    }

    private static ReferenceRecord copy(ReferenceRecord source, String deterministicAnswer) {
        return new ReferenceRecord(source.referenceId(), source.planId(), source.nodeId(), source.capability(),
                source.status(), source.summary(), deterministicAnswer, source.evidences(), source.candidateEntityIds(),
                source.verifiedEntityIds(), source.metadata());
    }

    private static boolean isStructuredShape(ReferenceRecord reference, String shape) {
        if (reference == null || reference.metadata() == null) return false;
        if (!reference.metadata().containsKey("normalizedPlan")) return false;
        return shape.equals(String.valueOf(reference.metadata().get("shape")));
    }

    private static List<Map<String, Object>> dataflowRows(ReferenceRecord reference) {
        if (reference == null || reference.metadata() == null) return List.of();
        Object raw = reference.metadata().get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) row.put(String.valueOf(key), value);
            });
            out.add(row);
        }
        return List.copyOf(out);
    }

    private static Set<String> referencedNodeIds(Object raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectReferences(raw, out);
        return Set.copyOf(out);
    }

    private static void collectReferences(Object raw, Set<String> out) {
        if (raw instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref != null && StrUtil.isNotBlank(String.valueOf(ref))) out.add(String.valueOf(ref));
            for (Object value : map.values()) collectReferences(value, out);
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) collectReferences(value, out);
        }
    }

    private static String stripListPrefix(String raw) {
        String line = raw == null ? "" : raw.trim();
        int dot = line.indexOf(". ");
        if (dot <= 0) return line;
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(line.charAt(i))) return line;
        }
        return line.substring(dot + 2).trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record GroupFact(ReferenceRecord reference, String groupKey) {
    }

    private record DetailEntity(String entityName, Set<String> aliases) {
    }

    private static final class MutableDetail {
        private final String entityName;
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();

        private MutableDetail(String entityName) {
            this.entityName = entityName;
        }
    }
}
