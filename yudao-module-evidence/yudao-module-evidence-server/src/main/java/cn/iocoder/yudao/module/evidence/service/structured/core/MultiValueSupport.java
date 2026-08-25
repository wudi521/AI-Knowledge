package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 多值字段唯一解析入口。
 *
 * <p>物理存储里的“一个字符串”只有按 FieldDefinition 声明的 delimiter contract 拆开后，
 * 才能参与 explode/filter/group/count/transform。禁止 Adapter/Executor 各自维护不同分隔正则。</p>
 */
public final class MultiValueSupport {

    /**
     * 通用安全默认：中文/英文标点、管道、换行、tab、NBSP、全角空格，以及 2 个以上 ASCII 空格。
     * 故意不把“单个 ASCII 空格”视为分隔符，以保留 John Smith 等人名/机构名内部空格。
     */
    public static final String DEFAULT_DELIMITER_REGEX =
            "(?:[、；;，,|\\n\\r\\t\\u3000\\u00A0]+| {2,})";

    private MultiValueSupport() {
    }

    public static List<String> split(String raw, FieldDefinition field) {
        String regex = field == null ? null : field.getMultiValueDelimiterRegex();
        return split(raw, regex);
    }

    public static List<String> split(String raw, String delimiterRegex) {
        if (StrUtil.isBlank(raw)) return List.of();
        String regex = StrUtil.blankToDefault(delimiterRegex, DEFAULT_DELIMITER_REGEX);
        String[] parts;
        try {
            parts = Pattern.compile(regex).split(raw);
        } catch (PatternSyntaxException e) {
            // Schema 配置错误不能把值吞掉；保守退回平台默认解析规则。
            parts = Pattern.compile(DEFAULT_DELIMITER_REGEX).split(raw);
        }
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (StrUtil.isNotBlank(part)) values.add(part.trim());
        }
        return values.isEmpty() ? List.of(raw.trim()) : List.copyOf(values);
    }

    public static String canonical(String raw, FieldDefinition field) {
        return canonical(raw, field == null ? null : field.getMultiValueDelimiterRegex());
    }

    public static String canonical(String raw, String delimiterRegex) {
        List<String> items = new ArrayList<>(new LinkedHashSet<>(split(raw, delimiterRegex)));
        items.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join("、", items);
    }

    public static Set<String> normalizedSet(String raw, FieldDefinition field) {
        return normalizedSet(raw, field == null ? null : field.getMultiValueDelimiterRegex());
    }

    public static Set<String> normalizedSet(String raw, String delimiterRegex) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : split(raw, delimiterRegex)) {
            String value = normalizeComparable(item);
            if (StrUtil.isNotBlank(value)) normalized.add(value);
        }
        return normalized;
    }

    public static String normalizeComparable(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
