package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Query Level-1 确定性 PreParser(Platform Core 领域无关)。
 * <p>
 * 职责: 只识别"结构化候选信号"(数量词/聚合词/排序词/指代词/量词), 产出 {@link PreParsedQuery}。
 * 禁止在此直接把"多少/几个"映射为 COUNT DOCUMENT —— metric/operation/scope 由后续 Registry + Resolver 决定。
 */
@Component
public class StructuredQueryPreParser {

    private static final Pattern CARDINALITY = Pattern.compile(
            "(?<!\\d)([0-9]+|一|二|两|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十)(?=\\s*(个|篇|件|份|条|项|部|款|家))");

    private static final String[] SCOPE_REFERENCE_WORDS = {
            "这个", "这些", "这三个", "这两个", "这", "它们", "上述", "上面的", "前面",
            "刚才那", "刚才的", "刚才提到", "其中", "那几个", "那几个", "那些", "该",
    };
    private static final String[] AGGREGATE_WORDS = {
            "多少", "几个", "总共", "共有", "一共", "合计", "数量", "总数", "总数",
            "平均", "最多", "最少", "最大", "最小", "最高", "最低", "占比", "排名",
    };
    private static final String[] LIST_WORDS = {"分别", "有哪些", "分别是哪些", "分别是什么", "列举", "列出"};
    private static final String[] SORT_WORDS = {"最多", "最少", "最大", "最小", "最高", "最低", "排名"};
    private static final String[] AVERAGE_WORDS = {"平均"};
    private static final String[] COUNT_WORDS = {"几个", "多少", "数量", "总数", "总共有", "一共有"};

    /**
     * 解析结构化候选信号(纯文本特征, 不依赖领域)。
     */
    public PreParsedQuery parse(String query) {
        if (StrUtil.isBlank(query)) {
            return PreParsedQuery.builder().aggregate(false).build();
        }
        String q = query.trim();
        boolean aggregate = containsAny(q, AGGREGATE_WORDS);
        boolean scopeReference = containsAny(q, SCOPE_REFERENCE_WORDS);
        boolean listIntent = containsAny(q, LIST_WORDS);
        boolean sortIntent = containsAny(q, SORT_WORDS);
        boolean averageIntent = containsAny(q, AVERAGE_WORDS);
        boolean countIntent = containsAny(q, COUNT_WORDS);
        Integer cardinality = extractCardinality(q);
        return PreParsedQuery.builder()
                .query(q)
                .aggregate(aggregate)
                .scopeReference(scopeReference)
                .listIntent(listIntent)
                .sortIntent(sortIntent)
                .averageIntent(averageIntent)
                .countIntent(countIntent)
                .cardinality(cardinality)
                .build();
    }

    /** 抽取数量词(如 "三个专利" → 3; 用于判断范围对象数) */
    private Integer extractCardinality(String query) {
        Matcher m = CARDINALITY.matcher(query);
        if (m.find()) {
            String token = m.group(1);
            Integer n = toNumber(token);
            return n != null && n > 0 ? n : null;
        }
        return null;
    }

    private Integer toNumber(String token) {
        if (StrUtil.isNumeric(token)) return Integer.parseInt(token);
        return switch (token) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            case "十三" -> 13;
            case "十四" -> 14;
            case "十五" -> 15;
            case "十六" -> 16;
            case "十七" -> 17;
            case "十八" -> 18;
            case "十九" -> 19;
            case "二十" -> 20;
            default -> null;
        };
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /** Level-1 结构化候选信号(不含任何领域/指标决策) */
    @Data
    @Builder
    public static class PreParsedQuery {

        /** 原始问题 */
        private String query;

        /** 命中聚合语义词(多少/总共/共有/平均/最多...) */
        private boolean aggregate;

        /** 命中范围指代(这个/这些/三个/它们/上述/前面的/刚才那几份/其中) */
        private boolean scopeReference;

        /** 命中列举语义(分别/有哪些/列举) */
        private boolean listIntent;

        /** 命中排序语义(最多/最少/最大/最小/排名/前N) */
        private boolean sortIntent;

        /** 命中平均语义 */
        private boolean averageIntent;

        /** 命中计数语义(几个/多少/数量/总数) */
        private boolean countIntent;

        /** 数量词(如 "三个" → 3; 用于范围对象数判定) */
        private Integer cardinality;

    }
}
