package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0 LLM 的保守 Filter Resolver。
 * <p>
 * 只解析带明确字段别名 + 明确运算词 + 明确值的条件；解析不了就不生成 filter，禁止猜。
 * 当前支持：字段=值、字段包含值、字段以值开头；多条件显式“或/或者”→OR，否则 AND。
 */
public final class SimpleStructuredFilterResolver {

    private SimpleStructuredFilterResolver() {}

    public static FilterExpression resolve(String query, String domainCode, DomainFieldRegistry registry) {
        if (StrUtil.isBlank(query) || StrUtil.isBlank(domainCode) || registry == null) return null;
        List<FilterExpression> conditions = new ArrayList<>();
        for (FieldDefinition field : registry.all(domainCode)) {
            if (field == null || !field.isFilterable() || field.getAliases() == null) continue;
            for (String alias : field.getAliases()) {
                if (StrUtil.isBlank(alias) || !query.contains(alias)) continue;
                FilterExpression condition = parseCondition(query, alias, field.getFieldCode());
                if (condition != null) {
                    conditions.add(condition);
                    break;
                }
            }
        }
        if (conditions.isEmpty()) return null;
        if (conditions.size() == 1) return conditions.get(0);
        return StrUtil.containsAny(query, "或者", " 或 ", "或是")
                ? FilterExpression.or(conditions) : FilterExpression.and(conditions);
    }

    private static FilterExpression parseCondition(String query, String alias, String fieldCode) {
        String q = Pattern.quote(alias);
        Match contains = match(query, q + "\\s*(?:包含|含有|含)\\s*[“\"']?([^，,。；;？?且或]{1,80})[”\"']?");
        if (contains != null) {
            return FilterExpression.condition(fieldCode, FilterOperator.CONTAINS, List.of(clean(contains.value())));
        }
        Match starts = match(query, q + "\\s*(?:以)\\s*[“\"']?([^，,。；;？?]{1,80})[”\"']?\\s*(?:开头|起始)");
        if (starts != null) {
            return FilterExpression.condition(fieldCode, FilterOperator.STARTS_WITH, List.of(clean(starts.value())));
        }
        Match eq = match(query, q + "\\s*(?:为|是|等于|=)\\s*[“\"']?([^，,。；;？?且或]{1,80})[”\"']?");
        if (eq != null) {
            return FilterExpression.condition(fieldCode, FilterOperator.EQ, List.of(clean(eq.value())));
        }
        return null;
    }

    private static Match match(String query, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(query);
        if (!matcher.find()) return null;
        String value = clean(matcher.group(1));
        return StrUtil.isBlank(value) ? null : new Match(value);
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.trim().replaceAll("[”\"']$", "").trim();
    }

    private record Match(String value) {}
}
