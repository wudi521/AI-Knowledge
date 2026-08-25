package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 公共 Relation Traversal Typed Tool。具体关系由 DomainRelationProvider 提供。
 */
@Component
public class RelationTraversalCapability implements KnowledgeCapability {
    public static final String NAME = "relation_traversal";
    private static final int MAX_SOURCE_IDS = 100;
    private static final int MAX_OUTPUT_IDS = 200;

    private final Map<String, DomainRelationProvider> providers;

    public RelationTraversalCapability(List<DomainRelationProvider> providers) {
        Map<String, DomainRelationProvider> mapped = new LinkedHashMap<>();
        if (providers != null) {
            for (DomainRelationProvider provider : providers) {
                if (provider == null || StrUtil.isBlank(provider.domainCode())) continue;
                String domain = normalize(provider.domainCode());
                if (mapped.putIfAbsent(domain, provider) != null) {
                    throw new IllegalStateException("duplicate DomainRelationProvider for domain: " + domain);
                }
            }
        }
        this.providers = Map.copyOf(mapped);
    }

    @Override
    public CapabilityDefinition definition() {
        Set<String> supportedDomains = providers.isEmpty()
                ? Set.of("__NO_RELATION_PROVIDER__") : new LinkedHashSet<>(providers.keySet());
        return new CapabilityDefinition(NAME, "1",
                "遍历当前领域已注册的真实实体关系。relationType 必须来自对应 Domain Provider；"
                        + "sourceEntityIds 通常通过 DAG $ref selector=verifiedEntityIds 引用上游节点。",
                Map.of(
                        "sourceEntityIds", "必填。1~100 个已验证实体 ID。",
                        "relationType", "必填。当前 Domain Provider 声明的关系类型。",
                        "direction", "可选。OUT / IN / BOTH，默认 OUT。",
                        "limit", "可选。1~200，默认 50。"
                ),
                Set.of("sourceEntityIds", "relationType"), "RELATION_EDGE_SET", true,
                Set.of(), supportedDomains, Set.of(), 8_000L, MAX_OUTPUT_IDS);
    }

    @Override
    public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                           Map<String, Object> arguments) {
        DomainRelationProvider provider = provider(context);
        if (provider == null) return CapabilityArgumentValidation.invalid("no relation provider for current domain");
        List<Long> sourceIds = ids(arguments == null ? null : arguments.get("sourceEntityIds"));
        if (sourceIds.isEmpty() || sourceIds.size() > MAX_SOURCE_IDS) {
            return CapabilityArgumentValidation.invalid("sourceEntityIds must contain 1..100 positive ids");
        }
        String relationType = text(arguments == null ? null : arguments.get("relationType"));
        if (StrUtil.isBlank(relationType) || !supports(provider, relationType)) {
            return CapabilityArgumentValidation.invalid("relationType is not registered for current domain");
        }
        if (direction(arguments == null ? null : arguments.get("direction")) == null) {
            return CapabilityArgumentValidation.invalid("direction must be OUT, IN or BOTH");
        }
        if (arguments != null && arguments.get("limit") != null) {
            Integer limit = integer(arguments.get("limit"));
            if (limit == null || limit < 1 || limit > MAX_OUTPUT_IDS) {
                return CapabilityArgumentValidation.invalid("limit must be an integer between 1 and 200");
            }
        }
        return CapabilityArgumentValidation.ok();
    }

    @Override
    public String canonicalExecutionKey(CapabilityInvocationContext context, Map<String, Object> arguments) {
        List<Long> sourceIds = new ArrayList<>(ids(arguments == null ? null : arguments.get("sourceEntityIds")));
        sourceIds.sort(Long::compareTo);
        String relationType = normalize(text(arguments == null ? null : arguments.get("relationType")));
        DomainRelationProvider.Direction direction = direction(arguments == null ? null : arguments.get("direction"));
        int limit = intValue(arguments == null ? null : arguments.get("limit"), 50);
        return "source=" + sourceIds + ";relation=" + relationType + ";direction=" + direction + ";limit=" + limit;
    }

    @Override
    public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
        DomainRelationProvider provider = provider(context);
        if (provider == null) {
            return CapabilityResult.failure(CapabilityFailureType.CONFIGURATION,
                    AgentStopReason.CAPABILITY_UNAVAILABLE, "no relation provider for current domain");
        }
        List<Long> sourceIds = ids(arguments == null ? null : arguments.get("sourceEntityIds"));
        String relationType = text(arguments == null ? null : arguments.get("relationType"));
        DomainRelationProvider.Direction direction = direction(arguments == null ? null : arguments.get("direction"));
        int limit = intValue(arguments == null ? null : arguments.get("limit"), 50);
        if (sourceIds.isEmpty() || StrUtil.isBlank(relationType) || direction == null || !supports(provider, relationType)) {
            return CapabilityResult.recoverableFailure("invalid relation traversal arguments",
                    Map.of("errorKind", "RELATION_CONTRACT"));
        }

        DomainRelationProvider.RelationResult providerResult;
        try {
            providerResult = provider.traverse(new DomainRelationProvider.RelationRequest(
                    context, sourceIds, relationType, direction, limit));
        } catch (RuntimeException e) {
            return CapabilityResult.failure(CapabilityFailureType.DEPENDENCY,
                    AgentStopReason.NO_RELIABLE_EVIDENCE,
                    "domain relation provider failed: " + StrUtil.maxLength(e.getMessage(), 180));
        }
        if (providerResult == null) {
            return CapabilityResult.failure(CapabilityFailureType.DATA_INCOMPLETE,
                    AgentStopReason.NO_RELIABLE_EVIDENCE, "domain relation provider returned null");
        }

        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        for (List<Long> values : providerResult.edges().values()) {
            if (values != null) for (Long id : values) if (id != null) targets.add(id);
        }
        boolean truncated = targets.size() > limit;
        List<Long> targetIds = targets.stream().limit(limit).toList();
        Output output = new Output(relationType, direction.name(), sourceIds, targetIds,
                providerResult.edges(), providerResult.evidences(), truncated);
        Map<String, Object> metadata = new LinkedHashMap<>(providerResult.metadata());
        metadata.put("outputCount", targetIds.size());
        metadata.put("sourceEntityCount", sourceIds.size());
        metadata.put("relationType", relationType);
        metadata.put("direction", direction.name());
        metadata.put("completeDataset", providerResult.complete() && !truncated);
        metadata.put("outputComplete", providerResult.complete() && !truncated);
        metadata.put("authoritativeEmpty", providerResult.complete() && !truncated && targetIds.isEmpty());
        metadata.put("truncated", truncated);

        if (truncated || !providerResult.complete()) {
            return CapabilityResult.partial(output,
                    StrUtil.blankToDefault(providerResult.message(), "relation traversal returned a partial result"), metadata);
        }
        if (targetIds.isEmpty()) {
            return CapabilityResult.empty(output,
                    StrUtil.blankToDefault(providerResult.message(), "relation traversal produced no related entity"), metadata);
        }
        return CapabilityResult.success(output, metadata);
    }

    private DomainRelationProvider provider(CapabilityInvocationContext context) {
        if (context == null || StrUtil.isBlank(context.domainCode())) return null;
        return providers.get(normalize(context.domainCode()));
    }

    private boolean supports(DomainRelationProvider provider, String relationType) {
        String normalized = normalize(relationType);
        if (provider.relationTypes() == null) return false;
        for (String type : provider.relationTypes()) {
            if (normalized.equals(normalize(type))) return true;
        }
        return false;
    }

    private DomainRelationProvider.Direction direction(Object raw) {
        if (raw == null) return DomainRelationProvider.Direction.OUT;
        try {
            return DomainRelationProvider.Direction.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> ids(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Object value : values) {
            Long id = id(value);
            if (id == null) return List.of();
            out.add(id);
        }
        return List.copyOf(out);
    }

    private Long id(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (raw == null) return null;
        try {
            long value = Long.parseLong(String.valueOf(raw).trim());
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer integer(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        if (raw == null) return null;
        try { return Integer.parseInt(String.valueOf(raw).trim()); }
        catch (Exception e) { return null; }
    }

    private int intValue(Object raw, int defaultValue) {
        Integer value = integer(raw);
        return value == null ? defaultValue : Math.max(1, Math.min(MAX_OUTPUT_IDS, value));
    }

    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record Output(String relationType,
                         String direction,
                         List<Long> sourceEntityIds,
                         List<Long> verifiedEntityIds,
                         Map<Long, List<Long>> edges,
                         List<Evidence> evidences,
                         boolean truncated) implements AgentCapabilityOutput {
        public Output {
            sourceEntityIds = sourceEntityIds == null ? List.of() : List.copyOf(sourceEntityIds);
            verifiedEntityIds = verifiedEntityIds == null ? List.of() : List.copyOf(verifiedEntityIds);
            edges = edges == null ? Map.of() : Map.copyOf(edges);
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
        }

        @Override
        public String summary() {
            return "relation=" + relationType + ", direction=" + direction
                    + ", sourceCount=" + sourceEntityIds.size()
                    + ", targetCount=" + verifiedEntityIds.size()
                    + (truncated ? ", truncated=true" : "");
        }

        @Override
        public String progressHash() {
            return relationType + ":" + direction + ":" + sourceEntityIds + "->" + verifiedEntityIds;
        }
    }
}
