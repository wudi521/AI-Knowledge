package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Completeness Guard(Platform Core 领域无关)。
 * <p>
 * 检测需要"完整数据集"才能回答的语义(总共有/一共/全部/所有/数量/占比/平均/最大/最小/最多/最少/排名/分别有哪些)。
 * <p>
 * 核心约束: RAG TopK 永远不能证明全集。任何需要全集结论的查询必须切到 Structured Query,
 * 或 answerable=false / CLARIFY; 禁止基于 BM25/Vector/Rerank TopK 召回猜测"知识库只有 N 条"。
 * <p>
 * Guard 必须位于 Generate 之前(由 EvidenceService 在检索/生成前调用)。
 */
@Component
public class CompletenessGuard {

    /** 完整数据集语义词(命中 → 必须结构化或拒绝, 禁止 TopK 路径) */
    private static final String[] COMPLETENESS_WORDS = {
            "总共有", "一共有", "一共", "总共", "全部", "所有", "分别有", "分别是什么",
            "数量", "占比", "平均", "最大", "最小", "最多", "最少", "最高", "最低", "排名",
            "合计", "总共多少", "共有多少", "多少个", "几个", "多少", "分别是",
    };

    /** 结构化候选信号(这些词只能用于判断 candidate, 禁止直接决定 COUNT DOCUMENT) */
    private static final String[] STRUCTURED_CANDIDATE_WORDS = {
            "多少", "几个", "总共", "共有", "一共", "合计", "数量", "总数",
            "平均", "最多", "最少", "最大", "最小", "最高", "最低", "占比", "排名",
            "分别", "有哪些", "分别是哪些", "列举", "列出",
    };

    /** 显式对象标识(申请号/公布号式编号) → 精确对象定位, 不属聚合候选 */
    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");

    /** 裸范围指代跟进(如 "这三个呢？" / "它们呢？" / "其中呢？") → 延续上一轮结构化语义 */
    private static final Pattern BARE_SCOPE_FOLLOW_UP = Pattern.compile(
            "^(这(个|些|几个|几件|两个|两件|三个|三件|几篇)|它们|上述|前面|上面|其中|那(些|几个|几件|几篇))[呢啊呀]?[？?]?$");

    /**
     * 该查询是否命中"完整数据集"语义 → 必须走 Structured Query 或拒绝, 禁止 TopK RAG 猜测全集结论。
     */
    public boolean requiresCompleteDataset(String query) {
        if (StrUtil.isBlank(query)) return false;
        return containsAny(query, COMPLETENESS_WORDS);
    }

    /** 该查询是否结构化查询候选(命中即进入 Structured Query Planner; 未命中继续原有 RAG 路径) */
    public boolean isStructuredCandidate(String query) {
        if (StrUtil.isBlank(query)) return false;
        // 明确的对象标识查询(EXACT_LOOKUP)不属于结构化聚合候选, 交给既有 EXACT 路径
        if (isExactLookup(query)) return false;
        // 裸范围指代跟进("这三个呢") → 结构化候选(需结合上下文消解 scope/metric)
        if (isBareScopeFollowUp(query)) return true;
        return containsAny(query, STRUCTURED_CANDIDATE_WORDS);
    }

    private boolean isBareScopeFollowUp(String query) {
        return BARE_SCOPE_FOLLOW_UP.matcher(query.trim()).matches();
    }

    /** 精确对象定位(申请号/公布号文本或编号), 由既有 EXACT_METADATA/EXACT_CLAIM 处理 */
    private boolean isExactLookup(String query) {
        if (query.contains("申请号") || query.contains("公布号") || query.contains("公开号")) return true;
        return APPLICATION_NO.matcher(query).find() || PUBLICATION_NO.matcher(query).find();
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
