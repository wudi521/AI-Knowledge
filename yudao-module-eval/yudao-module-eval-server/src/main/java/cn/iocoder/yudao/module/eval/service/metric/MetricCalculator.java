package cn.iocoder.yudao.module.eval.service.metric;

import cn.iocoder.yudao.module.eval.framework.eval.EvalProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 指标计算器: 逐题达标判定(纯静态工具, 无 Spring 依赖, 可单测)
 * <p>
 * 输入:
 * <ul>
 *     <li>goldChunks: 标准证据(期望命中的 chunkId 列表, ai_eval_case.gold_chunks);</li>
 *     <li>resultChunks: 检索结果顺序(evidence[] 按得分降序 → chunkId 列表, ai_eval_result.result_chunks);</li>
 *     <li>claims: 逐句断言验证结果(ai_eval_result.claims), evidenceIndex = 断言在 evidence[]/resultChunks 中的位置;</li>
 *     <li>gate: 达标闸门阈值(yudao.eval.gate.*)。</li>
 * </ul>
 * 约定:
 * <ul>
 *     <li>gold 为空 → Recall@5 / MRR / NDCG@5 均取 1.0(无期望即无遗漏, 不因空标准证据误判失败);</li>
 *     <li>resultChunks 为空且 gold 非空 → Recall@5 = 0;</li>
 *     <li>claims 为空 → 忠实度/幻觉率 = 0(无验证结果即不达标); 无引用 → 引用准确率 = 0;</li>
 *     <li>gate.enabled=false → 仅计算指标, passed 恒 true;</li>
 *     <li>所有比率取值 0~1(防御性钳制), 保留 4 位小数(对齐 decimal(5,4) 列)。</li>
 * </ul>
 */
public final class MetricCalculator {

    private MetricCalculator() {
    }

    /**
     * 单条断言验证结果(从 ai_eval_result.claims JSON 解析而来; 仅指标计算所需字段)
     *
     * @param verdict        判定: SUPPORTED / UNSUPPORTED
     * @param evidenceIndex  支撑证据在 evidence[]/resultChunks 中的位置索引(0 起; -1 或 null = 无支撑)
     */
    public record ClaimRecord(String verdict, Integer evidenceIndex) {

        public boolean isSupported() {
            return verdict != null && "SUPPORTED".equalsIgnoreCase(verdict.trim());
        }

    }

    /**
     * 单题指标结果
     */
    public record MetricResult(BigDecimal recallAt5, BigDecimal mrr, BigDecimal ndcg,
                               BigDecimal faithfulness, BigDecimal hallucinationRate, BigDecimal citationAccuracy,
                               boolean passed, String failReasons) {
    }

    /**
     * 计算单题全部指标并判定是否达标
     *
     * @param goldChunks    标准证据 chunkId 列表(可为空)
     * @param resultChunks  检索结果顺序 chunkId 列表(可为空)
     * @param claims        断言验证结果(可为空)
     * @param gate          达标闸门配置(为空时按配置默认值兜底, 见 {@link EvalProperties.Gate})
     */
    public static MetricResult compute(List<Long> goldChunks, List<Long> resultChunks, List<ClaimRecord> claims,
                                       EvalProperties.Gate gate) {
        List<Long> gold = normalize(goldChunks);
        List<Long> result = normalize(resultChunks);
        List<ClaimRecord> claimList = claims == null ? List.of() : claims;
        // 闸门兜底: 取配置默认值(不硬编码阈值)
        EvalProperties.Gate g = gate != null ? gate : new EvalProperties.Gate();

        // 1. Recall@5: |gold ∩ top5(resultChunks)| / |gold|(集合语义去重; gold 为空 → 1.0)
        Set<Long> goldSet = new HashSet<>(gold);
        Set<Long> top5 = new HashSet<>(result.subList(0, Math.min(5, result.size())));
        top5.retainAll(goldSet);
        double recallAt5 = goldSet.isEmpty() ? 1.0 : (double) top5.size() / goldSet.size();

        // 2. MRR: 首个命中位置(1-based)的倒数; 无命中 → 0; gold 为空 → 1.0(与 Recall/NDCG 空 gold 语义一致)
        double mrr;
        if (goldSet.isEmpty()) {
            mrr = 1.0;
        } else {
            int rank = -1;
            for (int i = 0; i < result.size(); i++) {
                if (goldSet.contains(result.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            mrr = rank > 0 ? 1.0 / rank : 0.0;
        }

        // 3. NDCG@5: DCG = Σ rel_i / log2(i+2)(i 为 0 起位置, 取 top5) / IDCG(命中全部前置的理想排序)
        //    gold 为空 → 1.0; gold 非空但零命中 → 0(避免 0/0)
        double ndcg;
        if (goldSet.isEmpty()) {
            ndcg = 1.0;
        } else {
            int numHits = 0; // result 全量中的命中数(用于 IDCG)
            double dcg = 0;
            for (int i = 0; i < result.size(); i++) {
                if (goldSet.contains(result.get(i))) {
                    numHits++;
                    if (i < 5) {
                        dcg += 1.0 / log2(i + 2);
                    }
                }
            }
            double idcg = 0;
            for (int i = 0; i < Math.min(5, numHits); i++) {
                idcg += 1.0 / log2(i + 2);
            }
            ndcg = idcg > 0 ? dcg / idcg : 0.0;
        }

        // 4. 忠实度 / 幻觉率: SUPPORTED / UNSUPPORTED 占比; 无断言 → 0
        int supported = 0;
        int unsupported = 0;
        for (ClaimRecord claim : claimList) {
            if (claim.isSupported()) {
                supported++;
            } else {
                unsupported++;
            }
        }
        double faithfulness = claimList.isEmpty() ? 0.0 : (double) supported / claimList.size();
        double hallucinationRate = claimList.isEmpty() ? 0.0 : (double) unsupported / claimList.size();

        // 5. 引用准确率: 引用 = 断言 SUPPORTED 且 evidenceIndex 在界内的 resultChunks[idx](去重);
        //    |引用 ∩ gold| / |引用|; 无引用 → 0
        Set<Long> citations = new LinkedHashSet<>();
        for (ClaimRecord claim : claimList) {
            if (!claim.isSupported() || claim.evidenceIndex() == null) {
                continue;
            }
            int idx = claim.evidenceIndex();
            if (idx >= 0 && idx < result.size()) {
                citations.add(result.get(idx));
            }
        }
        int citedHits = 0;
        for (Long citation : citations) {
            if (goldSet.contains(citation)) {
                citedHits++;
            }
        }
        double citationAccuracy = citations.isEmpty() ? 0.0 : (double) citedHits / citations.size();

        // 6. 达标判定 + 未达标原因
        boolean passed;
        String failReasons = null;
        if (!g.isEnabled()) {
            // 闸门关闭: 仅计算指标, 不做达标判定
            passed = true;
        } else {
            List<String> reasons = new ArrayList<>();
            if (recallAt5 < g.getRecallAt5()) {
                reasons.add("Recall@5 " + fmt(recallAt5) + "<" + fmt(g.getRecallAt5()));
            }
            if (mrr < g.getMrr()) {
                reasons.add("MRR " + fmt(mrr) + "<" + fmt(g.getMrr()));
            }
            if (ndcg < g.getNdcg()) {
                reasons.add("NDCG@5 " + fmt(ndcg) + "<" + fmt(g.getNdcg()));
            }
            if (faithfulness < g.getFaithfulness()) {
                reasons.add("忠实度 " + fmt(faithfulness) + "<" + fmt(g.getFaithfulness()));
            }
            if (hallucinationRate > g.getHallucination()) {
                reasons.add("幻觉率 " + fmt(hallucinationRate) + ">" + fmt(g.getHallucination()));
            }
            if (citationAccuracy < g.getCitationAccuracy()) {
                reasons.add("引用准确率 " + fmt(citationAccuracy) + "<" + fmt(g.getCitationAccuracy()));
            }
            passed = reasons.isEmpty();
            if (!passed) {
                failReasons = String.join("; ", reasons);
            }
        }

        return new MetricResult(round4(recallAt5), round4(mrr), round4(ndcg),
                round4(faithfulness), round4(hallucinationRate), round4(citationAccuracy),
                passed, failReasons);
    }

    /**
     * 空值/元素过滤归一化: null/空 → 空列表; 剔除 null 元素
     */
    private static List<Long> normalize(List<Long> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).toList();
    }

    /**
     * log2(x)
     */
    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    /**
     * 钳制 0~1 并保留 4 位小数(对齐 decimal(5,4) 列)
     */
    private static BigDecimal round4(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            value = 0.0;
        }
        value = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 展示格式: 先按存储精度(4 位小数)取整再去除尾部多余的 0(如 0.5000 → "0.5"), 便于 fail_reasons 可读
     */
    private static String fmt(double value) {
        return round4(value).stripTrailingZeros().toPlainString();
    }

}
