package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression;
import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression.Type;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SubsetParser(CQ-04/05/06): 解析查询中的指代/子集/序号/数量表达式(领域无关)。
 * <p>
 * 支持: 它们/这些/那些/上述/前面/刚才(→ALL), 第N个(→INDEX), 前N个(→FIRST_N), 后N个(→LAST_N),
 * 最后一个(→LAST_N(1)), 除了第N个(→EXCLUDE_INDEX), 除了前N个(→EXCLUDE_FIRST_N),
 * 这N个/其中N个/N个(→CARDINALITY, 数量与引用集合一致才可解析)。
 */
public final class SubsetParser {

    private static final String NUM = "(?:[0-9]{1,3}|[一二两三四五六七八九十]{1,3})";
    private static final String CLS = "(?:个|篇|件|份|条|项|部|款|家)";

    private static final Pattern INDEX_P = Pattern.compile("第(" + NUM + ")" + CLS + "?");
    private static final Pattern FIRST_P = Pattern.compile("前(" + NUM + ")" + CLS + "?");
    private static final Pattern LAST_P = Pattern.compile("后(" + NUM + ")" + CLS + "?");
    private static final Pattern EXCLUDE_INDEX_P = Pattern.compile("除了第(" + NUM + ")" + CLS + "?");
    private static final Pattern EXCLUDE_FIRST_P = Pattern.compile("除了前(" + NUM + ")" + CLS + "?");
    private static final Pattern CARDINALITY_P = Pattern.compile("(?:这|那|其中|上述|前面|刚才)?(这)?(" + NUM + ")" + CLS);
    private static final Pattern PRONOUN_P = Pattern.compile("它们|它们?|这些|那些|上述|前面|刚才|这几个|那几个|其中几个|其它几个");

    private SubsetParser() {
    }

    /**
     * 解析子集/指代表达式。
     *
     * @return SubsetExpression; 查询无指代/子集时返回 null
     */
    public static SubsetExpression parse(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        // 序数/前后/除了(优先级高于通用数量词)
        Matcher ei = EXCLUDE_INDEX_P.matcher(query);
        if (ei.find()) {
            return subset(Type.EXCLUDE_INDEX, toNumber(ei.group(1)));
        }
        Matcher ef = EXCLUDE_FIRST_P.matcher(query);
        if (ef.find()) {
            return subset(Type.EXCLUDE_FIRST_N, toNumber(ef.group(1)));
        }
        Matcher idx = INDEX_P.matcher(query);
        if (idx.find()) {
            int n = toNumber(idx.group(1));
            if ("最后".contains(query)) {
                return null;
            }
            return subset(Type.INDEX, n);
        }
        Matcher f = FIRST_P.matcher(query);
        if (f.find()) {
            return subset(Type.FIRST_N, toNumber(f.group(1)));
        }
        Matcher l = LAST_P.matcher(query);
        if (l.find()) {
            return subset(Type.LAST_N, toNumber(l.group(1)));
        }
        // 指代词(无数量) → ALL
        if (PRONOUN_P.matcher(query).find()) {
            return subset(Type.ALL, null);
        }
        // "最后一个" 特殊
        if (query.contains("最后一个")) {
            return subset(Type.LAST_N, 1);
        }
        // 数量词(这N个/其中N个/N个) → CARDINALITY(数量一致才解析)
        Matcher c = CARDINALITY_P.matcher(query);
        if (c.find()) {
            int n = toNumber(c.group(2));
            if (n > 0) {
                return subset(Type.CARDINALITY, n);
            }
        }
        return null;
    }

    private static SubsetExpression subset(Type type, Integer param) {
        if (type == Type.INDEX || type == Type.EXCLUDE_INDEX) {
            return SubsetExpression.builder().type(type).index(param).build();
        }
        return SubsetExpression.builder().type(type).count(param).build();
    }

    /** 中文数字/阿拉伯数字 → int(一~二十); 无法解析返回 0 */
    static int toNumber(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (Character.isDigit(s.charAt(0))) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        // 中文数字(一~二十)
        char[] digits = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十'};
        if (s.equals("两")) {
            return 2;
        }
        if (s.length() == 1) {
            for (int i = 0; i < digits.length; i++) {
                if (digits[i] == s.charAt(0)) {
                    return i;
                }
            }
            return 0;
        }
        if (s.equals("二十")) {
            return 20;
        }
        if (s.startsWith("十")) {
            return 10 + indexOf(digits, s.charAt(1));
        }
        if (s.endsWith("十")) {
            return indexOf(digits, s.charAt(0)) * 10;
        }
        return 0;
    }

    private static int indexOf(char[] arr, char c) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == c) {
                return i;
            }
        }
        return 0;
    }

}
