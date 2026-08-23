package cn.iocoder.yudao.module.evidence.service.rule;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * AG-03/04: 知识库聚合确定性处理(KB_STATISTICS intent)。
 * <p>
 * 计数类问题(几个/多少/总数/数量)不走 TopK RAG —— TopK 召回不能推断整个知识库的总数。
 * 直接调用知识模块 aggregateCount 确定性回答: 0 QueryAnalysis LLM / 0 Embedding / 0 BM25 / 0 Vector /
 * 0 Fusion / 0 Rerank / 0 Generate / 0 Verify。
 * <p>
 * 未命中聚合(如 LIST 类"有哪些/全部/占比/平均/最大/最小")返回 null, 由调用方 Completeness Guard
 * 决定拒绝作答, 而不是根据 TopK 猜完整性结论。
 */
@Slf4j
@Component
public class AggregateHandler {

    private static final Pattern COUNT_PATTERN = Pattern.compile(
            "几个|多少|总共|总数|数量|共有.{0,6}(?:专利|文档|知识|条)");
    /** AG-06: 完整性/聚合深度问题(需全量数据, TopK 不可作答) */
    private static final Pattern COMPLETENESS_PATTERN = Pattern.compile(
            "有哪些|分别有哪些|全部|所有|占比|平均|最大|最小|分别是哪些|列举所有");

    @Resource
    private KnowledgeApi knowledgeApi;

    /** 聚合目标(metric) */
    public enum Metric {
        PATENT_COUNT, DOCUMENT_COUNT, KNOWLEDGE_ENTRY_COUNT
    }

    /** 聚合结果 */
    public record AggregateResult(Metric metric, int value, String answer, String filters) {
    }

    /** 是否完整性/聚合深度问题(命中 → 不允许 TopK RAG 猜测完整性结论) */
    public static boolean isCompletenessIntent(String query) {
        if (StrUtil.isBlank(query)) return false;
        return COUNT_PATTERN.matcher(query).find() || COMPLETENESS_PATTERN.matcher(query).find();
    }

    /** 命中可确定性回答的聚合 → 返回结果; 否则 null */
    public AggregateResult evaluate(String query, List<Long> kbIds) {
        if (StrUtil.isBlank(query) || kbIds == null || kbIds.size() != 1 || kbIds.get(0) == null) {
            return null;
        }
        Long kbId = kbIds.get(0);
        // LIST/深度聚合(有哪些/占比/平均/最大/最小) → 无聚合引擎, 交 Completeness Guard 拒绝
        if (COMPLETENESS_PATTERN.matcher(query).find()) {
            return null;
        }
        Metric metric = detectMetric(query);
        if (metric == null) {
            return null;
        }
        boolean publishedOnly = true;
        String domainCode = metric == Metric.PATENT_COUNT ? "PATENT" : null;
        try {
            Integer count = knowledgeApi.aggregateCount(kbId, metric.name(), publishedOnly, domainCode).getCheckedData();
            if (count == null) {
                return null;
            }
            String filters = "publishedOnly=" + publishedOnly
                    + (domainCode != null ? ",domainCode=" + domainCode : "");
            return new AggregateResult(metric, count, buildAnswer(metric, count), filters);
        } catch (Exception e) {
            log.warn("[evaluate][聚合统计失败, 走 Completeness Guard 拒绝: {}]", e.getMessage());
            return null;
        }
    }

    /** 由问题识别聚合目标(平台层通用; Domain Pack 后续可扩展) */
    private Metric detectMetric(String query) {
        if (!COUNT_PATTERN.matcher(query).find()) {
            return null;
        }
        if (StrUtil.containsAny(query, "专利", "申请号", "公布号")) {
            return Metric.PATENT_COUNT;
        }
        if (StrUtil.containsAny(query, "文档", "文献", "文件", "篇")) {
            return Metric.DOCUMENT_COUNT;
        }
        if (StrUtil.containsAny(query, "知识", "条目", "内容", "条知识")) {
            return Metric.KNOWLEDGE_ENTRY_COUNT;
        }
        // 仅有"几个/多少/总数"等 → 平台默认按文档数
        return Metric.DOCUMENT_COUNT;
    }

    private String buildAnswer(Metric metric, int count) {
        switch (metric) {
            case PATENT_COUNT:
                return "当前知识库共有 " + count + " 件已发布专利文献。";
            case DOCUMENT_COUNT:
                return "当前知识库共有 " + count + " 篇已发布文档。";
            case KNOWLEDGE_ENTRY_COUNT:
                return "当前知识库共有 " + count + " 条知识条目。";
            default:
                return "当前知识库统计结果为 " + count + "。";
        }
    }

}
