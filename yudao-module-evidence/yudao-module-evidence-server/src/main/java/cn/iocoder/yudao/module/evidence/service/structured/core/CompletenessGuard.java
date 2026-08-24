package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Completeness / Planner Entry Guard。
 * <p>
 * 识别两类入口：
 * 1. 必须完整数据集的 Structured 语义；
 * 2. 必须先经过 Query Planner V2 的比较/主观评价/精确原文语义。
 * 普通单文档语义问答暂时继续走稳定旧 RAG 主链。
 */
@Component
public class CompletenessGuard {

    private static final String[] COMPLETENESS_WORDS = {
            "总共有", "一共有", "一共", "总共", "全部", "所有", "分别有", "分别是什么",
            "数量", "占比", "平均", "最大", "最小", "最多", "最少", "最高", "最低", "排名",
            "合计", "总共多少", "共有多少", "多少个", "几个", "多少", "分别是",
    };

    private static final String[] STRUCTURED_CANDIDATE_WORDS = {
            "多少", "几个", "总共", "共有", "一共", "合计", "数量", "总数",
            "平均", "最多", "最少", "最大", "最小", "最高", "最低", "占比", "排名",
            "分别", "有哪些", "分别是哪些", "列举", "列出",
            "申请号", "公布号", "公开号", "申请人", "发明人", "申请日", "公开日",
    };

    /** 必须先过 Planner 的特殊语义，不能直接落普通 Hybrid TopK。 */
    private static final String[] PLANNER_ENTRY_WORDS = {
            // 跨实体比较
            "相似", "类似", "最像", "最接近", "共同点", "共性", "相同点",
            "区别", "差异", "不同点", "比较", "对比",
            // 主观评价：Planner 需要要求用户给评价标准
            "哪个好", "哪个更好", "更先进", "最先进", "更有价值", "最有价值", "最好", "最优", "更强", "最强",
            // 精确原文搜索：不能用语义 TopK 冒充 exact/complete
            "原文出现", "原文包含", "精确搜索", "精确匹配", "出现过"
    };

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
    private static final Pattern BARE_SCOPE_FOLLOW_UP = Pattern.compile(
            "^(这(个|些|几个|几件|两个|两件|三个|三件|几篇)|它们|上述|前面|上面|其中|那(些|几个|几件|几篇))[呢啊呀]?[？?]?$");
    private static final String[] LIST_CONTEXT_WORDS = {
            "分别", "哪些", "有几个", "几个", "它们", "这些", "那些", "分别是什么", "列举", "列出", "前面", "上述"
    };

    public boolean requiresCompleteDataset(String query) {
        return StrUtil.isNotBlank(query) && containsAny(query, COMPLETENESS_WORDS);
    }

    /**
     * 保留历史方法名兼容 EvidenceService；实际语义是“需要进入 Structured/Query Planner 的候选”。
     */
    public boolean isStructuredCandidate(String query) {
        if (StrUtil.isBlank(query)) return false;
        if (isExactLookup(query)) return false;
        if (BARE_SCOPE_FOLLOW_UP.matcher(query.trim()).matches()) return true;
        return containsAny(query, STRUCTURED_CANDIDATE_WORDS) || containsAny(query, PLANNER_ENTRY_WORDS);
    }

    private boolean isExactLookup(String query) {
        if (APPLICATION_NO.matcher(query).find() || PUBLICATION_NO.matcher(query).find()) return true;
        if (query.contains("申请号") || query.contains("公布号") || query.contains("公开号")) {
            return !containsAny(query, LIST_CONTEXT_WORDS);
        }
        return false;
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }
}
