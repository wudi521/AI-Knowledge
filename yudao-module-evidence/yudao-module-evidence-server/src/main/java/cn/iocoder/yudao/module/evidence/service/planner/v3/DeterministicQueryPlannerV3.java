package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Schema 驱动的确定性查询编译器。
 *
 * <p>它不枚举用户句式，只消费 Domain Field Schema 中有限的“标识符模式 + 字段别名”，
 * 将明确的“标识符定位对象并投影字段”查询编译成强类型 IR。无法无歧义编译时返回 empty，
 * 再由 LLM Planner 处理复杂语义。</p>
 */
@Component
public class DeterministicQueryPlannerV3 {

    private static final Pattern COMPLEX_RELATION = Pattern.compile(
            "(类似|相似|相关|涉及|采用|解决|对比|比较|区别|差异|为什么|如何|原因|影响)");

    private final DomainFieldRegistry fieldRegistry;

    public DeterministicQueryPlannerV3(DomainFieldRegistry fieldRegistry) {
        this.fieldRegistry = fieldRegistry;
    }

    public Optional<QueryIntentV3> tryPlan(String query, String domainCode) {
        if (StrUtil.isBlank(query) || StrUtil.isBlank(domainCode)) return Optional.empty();
        if (COMPLEX_RELATION.matcher(query).find()) return Optional.empty();
        long start = System.currentTimeMillis();

        List<IdentifierHit> identifiers = identifierHits(query, domainCode);
        if (identifiers.size() != 1) return Optional.empty();
        IdentifierHit source = identifiers.get(0);

        List<FieldHit> projections = projectionHits(query, domainCode, source);
        if (projections.isEmpty()) return Optional.empty();

        QueryIntentV3.Selection selection = QueryIntentV3.Selection.builder()
                .type(QueryIntentV3.SelectionType.EXACT_ENTITY)
                .field(source.field().getFieldCode())
                .operator(FilterOperator.EQ)
                .operatorRaw(FilterOperator.EQ.name())
                .values(List.of(source.value()))
                .build();
        QueryIntentV3.Action action = QueryIntentV3.Action.builder()
                .type(QueryIntentV3.ActionType.PROJECT_FIELDS)
                .fields(projections.stream().map(hit -> hit.field().getFieldCode()).distinct().toList())
                .build();

        return Optional.of(QueryIntentV3.builder()
                .version("3")
                .domainCode(domainCode)
                .entityType(source.field().getEntityType())
                .selection(selection)
                .actions(List.of(action))
                .completeness("COMPLETE_REQUIRED")
                .requiresClarification(false)
                .plannerStatus(QueryIntentV3.PlannerStatus.EXECUTABLE)
                .plannerSource("DETERMINISTIC_SCHEMA")
                .plannerElapsedMs(System.currentTimeMillis() - start)
                .build());
    }

    /** 返回 Schema 已注册的字面标识符事实，供 LLM 输入与计划归一化复用。 */
    public Map<String, List<String>> identifierValues(String query, String domainCode) {
        if (StrUtil.isBlank(query) || StrUtil.isBlank(domainCode)) return Map.of();
        return identifierHits(query, domainCode).stream().collect(Collectors.groupingBy(
                hit -> hit.field().getFieldCode(), LinkedHashMap::new,
                Collectors.mapping(IdentifierHit::value, Collectors.toList())));
    }

    private List<IdentifierHit> identifierHits(String query, String domainCode) {
        Map<String, IdentifierHit> unique = new LinkedHashMap<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field == null || !field.isExactIdentifier() || field.getIdentifierPatterns() == null) continue;
            for (String configuredPattern : field.getIdentifierPatterns()) {
                if (StrUtil.isBlank(configuredPattern)) continue;
                try {
                    Matcher matcher = Pattern.compile(configuredPattern).matcher(query);
                    while (matcher.find()) {
                        String value = matcher.group().trim();
                        String key = field.getFieldCode() + "\u0000" + normalizeIdentifier(value);
                        unique.putIfAbsent(key, new IdentifierHit(field, value, matcher.start(), matcher.end()));
                    }
                } catch (PatternSyntaxException ignored) {
                    // 非法领域配置不能击穿查询链路；Schema 契约测试负责阻止它进入发布版本。
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<FieldHit> projectionHits(String query, String domainCode, IdentifierHit source) {
        Map<String, FieldHit> unique = new LinkedHashMap<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field == null || field.getFieldCode() == null
                    || field.getFieldCode().equalsIgnoreCase(source.field().getFieldCode())
                    || field.getAliases() == null) continue;
            for (String alias : field.getAliases()) {
                if (StrUtil.isBlank(alias)) continue;
                int from = 0;
                while (from < query.length()) {
                    int index = query.indexOf(alias, from);
                    if (index < 0) break;
                    FieldHit hit = new FieldHit(field, index, alias.length());
                    FieldHit old = unique.get(field.getFieldCode());
                    if (old == null || projectionOrder(hit, source) < projectionOrder(old, source)) {
                        unique.put(field.getFieldCode(), hit);
                    }
                    from = index + alias.length();
                }
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(hit -> projectionOrder(hit, source)))
                .toList();
    }

    private int projectionOrder(FieldHit hit, IdentifierHit source) {
        // 标识符之后的别名通常是投影目标；其余确定字段仍按原文顺序保留，支持多字段投影。
        return hit.start() >= source.end() ? hit.start() : 1_000_000 + hit.start();
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase();
    }

    private record IdentifierHit(FieldDefinition field, String value, int start, int end) { }

    private record FieldHit(FieldDefinition field, int start, int length) { }
}
