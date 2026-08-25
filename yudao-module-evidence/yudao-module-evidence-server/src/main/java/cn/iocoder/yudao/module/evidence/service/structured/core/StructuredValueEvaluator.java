package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受控字段值执行器：多值展开、类型化比较和白名单变换都在这里统一实现。
 * 不执行脚本、不接收任意函数名。
 */
@Component
public class StructuredValueEvaluator {
    private static final Set<String> CHINESE_COMPOUND_SURNAMES = Set.of(
            "欧阳", "太史", "端木", "上官", "司马", "东方", "独孤", "南宫", "万俟", "闻人",
            "夏侯", "诸葛", "尉迟", "公羊", "赫连", "澹台", "皇甫", "宗政", "濮阳", "公冶",
            "太叔", "申屠", "公孙", "慕容", "仲孙", "钟离", "长孙", "宇文", "司徒", "鲜于",
            "司空", "闾丘", "子车", "亓官", "司寇", "巫马", "公西", "颛孙", "壤驷", "公良",
            "漆雕", "乐正", "宰父", "谷梁", "拓跋", "夹谷", "轩辕", "令狐", "段干", "百里",
            "呼延", "东郭", "南门", "羊舌", "微生", "梁丘", "左丘", "东门", "西门", "第五"
    );

    private final DomainFieldRegistry fieldRegistry;

    public StructuredValueEvaluator(DomainFieldRegistry fieldRegistry) {
        this.fieldRegistry = fieldRegistry;
    }

    public Validation validate(String domainCode, StructuredValueExpression expression) {
        if (expression == null || StrUtil.isBlank(expression.fieldCode())) return Validation.invalid("value field is required");
        FieldDefinition field = fieldRegistry.byCode(domainCode, expression.fieldCode()).orElse(null);
        if (field == null) return Validation.invalid("field is not registered: " + expression.fieldCode());
        Set<StructuredValueTransform> allowed = field.getAllowedTransforms() == null ? Set.of() : field.getAllowedTransforms();
        String type = field.getValueType();
        for (StructuredValueTransform transform : expression.transforms()) {
            if (!allowed.contains(transform)) {
                return Validation.invalid("transform " + transform + " is not allowed for field " + field.getFieldCode());
            }
            if (field.isMultiValue() && !expression.explode()
                    && transform != StructuredValueTransform.VALUE_COUNT) {
                return Validation.invalid("transform " + transform + " on multi-value field "
                        + field.getFieldCode() + " requires explode=true");
            }
            if (!supportsInput(transform, type, field.isMultiValue())) {
                return Validation.invalid("transform " + transform + " does not accept " + type + " on " + field.getFieldCode());
            }
            type = outputType(transform);
        }
        return Validation.valid(field, type);
    }

    public List<String> values(String domainCode, StructuredQueryResult.Row row, StructuredValueExpression expression) {
        return evaluate(domainCode, row, expression).values();
    }

    /**
     * 给 Pipeline/Trace 的机器诊断。不能只返回空集合，因为“原字段没值”和“字段有值但变换失败”
     * 对数据治理和可回答性是两种完全不同的事实。
     */
    public ValueEvaluation evaluate(String domainCode, StructuredQueryResult.Row row,
                                    StructuredValueExpression expression) {
        Validation validation = validate(domainCode, expression);
        if (!validation.valid()) {
            return ValueEvaluation.failure(FailureKind.CONTRACT_INVALID, validation.message(), null, List.of());
        }
        if (row == null || row.getFields() == null) {
            return ValueEvaluation.failure(FailureKind.RAW_MISSING, "source row/fields are missing", null, List.of());
        }
        String raw = row.getFields().get(validation.field().getFieldCode());
        if (StrUtil.isBlank(raw)) {
            return ValueEvaluation.failure(FailureKind.RAW_MISSING,
                    "raw field is missing: " + validation.field().getFieldCode(), raw, List.of());
        }

        List<String> source;
        boolean valueCount = expression.transforms().contains(StructuredValueTransform.VALUE_COUNT);
        if (valueCount) {
            source = List.of(String.valueOf(splitMulti(raw).size()));
        } else if (validation.field().isMultiValue() && expression.explode()) {
            source = splitMulti(raw);
        } else if (validation.field().isMultiValue()) {
            source = List.of(canonicalMulti(raw));
        } else {
            source = List.of(raw.trim());
        }
        if (source.isEmpty()) {
            return ValueEvaluation.failure(FailureKind.RAW_MISSING,
                    "raw multi-value field contains no usable element: " + validation.field().getFieldCode(), raw, List.of());
        }

        List<StructuredValueTransform> transforms = expression.transforms().stream()
                .filter(t -> t != StructuredValueTransform.VALUE_COUNT).toList();
        List<String> out = new ArrayList<>();
        for (String value : source) {
            String current = value;
            String currentType = valueCount ? "INTEGER" : validation.field().getValueType();
            if (!literalValid(currentType, current)) {
                return ValueEvaluation.failure(FailureKind.SOURCE_TYPE_INVALID,
                        "raw value is invalid for declared type " + currentType,
                        raw, List.of(value));
            }
            for (StructuredValueTransform transform : transforms) {
                String next = apply(transform, current, currentType);
                if (StrUtil.isBlank(next)) {
                    return ValueEvaluation.failure(FailureKind.TRANSFORM_FAILED,
                            "transform " + transform + " failed for field " + validation.field().getFieldCode(),
                            raw, List.of(value));
                }
                current = next;
                currentType = outputType(transform);
            }
            out.add(current);
        }
        if (out.isEmpty()) {
            return ValueEvaluation.failure(FailureKind.TRANSFORM_FAILED,
                    "value evaluation produced no output", raw, source);
        }
        return ValueEvaluation.success(List.copyOf(new LinkedHashSet<>(out)), raw);
    }

    public String outputType(String domainCode, StructuredValueExpression expression) {
        Validation validation = validate(domainCode, expression);
        return validation.valid() ? validation.outputType() : null;
    }

    public boolean literalsValid(String valueType, List<String> literals) {
        if (literals == null) return true;
        for (String literal : literals) if (!literalValid(valueType, literal)) return false;
        return true;
    }

    public int compare(String left, String right, String valueType) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if ("INTEGER".equalsIgnoreCase(valueType) || "DECIMAL".equalsIgnoreCase(valueType)) {
            try { return new BigDecimal(left.trim()).compareTo(new BigDecimal(right.trim())); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid numeric value for typed comparison");
            }
        }
        if ("DATE".equalsIgnoreCase(valueType)) {
            LocalDate a = date(left), b = date(right);
            if (a == null || b == null) throw new IllegalArgumentException("invalid date value for typed comparison");
            return a.compareTo(b);
        }
        return left.trim().toLowerCase(Locale.ROOT).compareTo(right.trim().toLowerCase(Locale.ROOT));
    }

    public boolean matches(FilterOperator operator, List<String> actualValues, List<String> expected, String valueType) {
        List<String> actual = actualValues == null ? List.of() : actualValues;
        List<String> exp = expected == null ? List.of() : expected;
        if (operator == FilterOperator.EXISTS) return !actual.isEmpty();
        if (operator == FilterOperator.NE) {
            if (actual.isEmpty()) return true;
            if (exp.isEmpty()) return true;
            return actual.stream().allMatch(v -> compare(v, exp.get(0), valueType) != 0);
        }
        if (actual.isEmpty()) return false;
        return actual.stream().anyMatch(v -> switch (operator) {
            case EQ -> !exp.isEmpty() && compare(v, exp.get(0), valueType) == 0;
            case CONTAINS -> !exp.isEmpty() && v.toLowerCase(Locale.ROOT).contains(exp.get(0).toLowerCase(Locale.ROOT));
            case STARTS_WITH -> !exp.isEmpty() && v.toLowerCase(Locale.ROOT).startsWith(exp.get(0).toLowerCase(Locale.ROOT));
            case IN -> exp.stream().anyMatch(e -> compare(v, e, valueType) == 0);
            case GT -> !exp.isEmpty() && compare(v, exp.get(0), valueType) > 0;
            case GTE -> !exp.isEmpty() && compare(v, exp.get(0), valueType) >= 0;
            case LT -> !exp.isEmpty() && compare(v, exp.get(0), valueType) < 0;
            case LTE -> !exp.isEmpty() && compare(v, exp.get(0), valueType) <= 0;
            case BETWEEN -> exp.size() >= 2 && compare(v, exp.get(0), valueType) >= 0 && compare(v, exp.get(1), valueType) <= 0;
            case EXISTS, NE -> false;
        });
    }

    private boolean literalValid(String valueType, String literal) {
        if (literal == null) return false;
        if ("INTEGER".equalsIgnoreCase(valueType)) {
            try { new BigDecimal(literal.trim()).toBigIntegerExact(); return true; }
            catch (Exception e) { return false; }
        }
        if ("DECIMAL".equalsIgnoreCase(valueType)) {
            try { new BigDecimal(literal.trim()); return true; }
            catch (NumberFormatException e) { return false; }
        }
        if ("DATE".equalsIgnoreCase(valueType)) return date(literal) != null;
        return true;
    }

    private boolean supportsInput(StructuredValueTransform transform, String type, boolean multiValue) {
        return switch (transform) {
            case LENGTH -> "STRING".equalsIgnoreCase(type);
            case YEAR, MONTH, YEAR_MONTH -> "DATE".equalsIgnoreCase(type);
            case VALUE_COUNT -> multiValue;
            case PERSON_SURNAME -> "STRING".equalsIgnoreCase(type);
        };
    }

    private String outputType(StructuredValueTransform transform) {
        return switch (transform) {
            case LENGTH, YEAR, MONTH, VALUE_COUNT -> "INTEGER";
            case YEAR_MONTH, PERSON_SURNAME -> "STRING";
        };
    }

    private String apply(StructuredValueTransform transform, String value, String currentType) {
        if (value == null) return null;
        return switch (transform) {
            case LENGTH -> String.valueOf(value.codePointCount(0, value.length()));
            case YEAR -> { LocalDate d = date(value); yield d == null ? null : String.valueOf(d.getYear()); }
            case MONTH -> { LocalDate d = date(value); yield d == null ? null : String.valueOf(d.getMonthValue()); }
            case YEAR_MONTH -> { LocalDate d = date(value); yield d == null ? null : String.format(Locale.ROOT, "%04d-%02d", d.getYear(), d.getMonthValue()); }
            case VALUE_COUNT -> value;
            case PERSON_SURNAME -> surname(value);
        };
    }

    private List<String> splitMulti(String raw) {
        if (StrUtil.isBlank(raw)) return List.of();
        String[] parts = raw.split("[、；;，,\\n\\r]+");
        List<String> values = new ArrayList<>();
        for (String part : parts) if (StrUtil.isNotBlank(part)) values.add(part.trim());
        return values.isEmpty() ? List.of(raw.trim()) : List.copyOf(values);
    }

    private String canonicalMulti(String raw) {
        List<String> items = new ArrayList<>(new LinkedHashSet<>(splitMulti(raw)));
        items.sort(Comparator.comparing(v -> v.toLowerCase(Locale.ROOT)));
        return String.join("、", items);
    }

    private String surname(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        String value = raw.trim();
        String compact = value.replaceAll("\\s+", "");
        if (compact.matches("[\\p{IsHan}·・]+")) {
            compact = compact.replace("·", "").replace("・", "");
            if (compact.length() < 2) return null;
            for (String compound : CHINESE_COMPOUND_SURNAMES) if (compact.startsWith(compound)) return compound;
            return compact.substring(0, 1);
        }
        String[] tokens = value.split("\\s+");
        if (tokens.length >= 2) {
            String last = tokens[tokens.length - 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            return StrUtil.isBlank(last) ? null : last;
        }
        return null;
    }

    private LocalDate date(String value) {
        if (StrUtil.isBlank(value)) return null;
        String text = value.trim();
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyy.MM.dd"),
                DateTimeFormatter.BASIC_ISO_DATE)) {
            try { return LocalDate.parse(text, formatter); }
            catch (DateTimeParseException ignore) { }
        }
        try { return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate(); }
        catch (DateTimeParseException ignore) { }
        try { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate(); }
        catch (DateTimeParseException ignore) { }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))) {
            try { return LocalDateTime.parse(text, formatter).toLocalDate(); }
            catch (DateTimeParseException ignore) { }
        }
        return null;
    }

    public enum FailureKind {
        NONE,
        CONTRACT_INVALID,
        RAW_MISSING,
        SOURCE_TYPE_INVALID,
        TRANSFORM_FAILED
    }

    public record ValueEvaluation(List<String> values,
                                  FailureKind failureKind,
                                  String message,
                                  String rawValue,
                                  List<String> failedElements) {
        public ValueEvaluation {
            values = values == null ? List.of() : List.copyOf(values);
            failedElements = failedElements == null ? List.of() : List.copyOf(failedElements);
        }

        public static ValueEvaluation success(List<String> values, String rawValue) {
            return new ValueEvaluation(values, FailureKind.NONE, null, rawValue, List.of());
        }

        public static ValueEvaluation failure(FailureKind kind, String message,
                                              String rawValue, List<String> failedElements) {
            return new ValueEvaluation(List.of(), kind, message, rawValue, failedElements);
        }

        public boolean success() {
            return failureKind == FailureKind.NONE;
        }
    }

    public record Validation(boolean valid, FieldDefinition field, String outputType, String message) {
        static Validation valid(FieldDefinition field, String outputType) { return new Validation(true, field, outputType, null); }
        static Validation invalid(String message) { return new Validation(false, null, null, message); }
    }
}
