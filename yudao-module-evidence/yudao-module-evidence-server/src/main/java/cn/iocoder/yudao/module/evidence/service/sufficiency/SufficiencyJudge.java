package cn.iocoder.yudao.module.evidence.service.sufficiency;

import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.Judgement;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 充分性判定器: 配置化规则(阈值/开关/权重全部来自 {@link EvidenceProperties}) + 置信度融合,
 * 输出 是否可作答/融合置信度/原因。
 * <p>
 * 领域无关设计: 不感知行业术语, "产品/品牌" 一律视为通用 "实体(entity)"。
 * <p>
 * 判定流程(门禁顺序固定, 原因可组合):
 * <ol>
 *     <li>检索阻断: 上游检索已判定阻断作答(品牌一致性门禁) → answerable=false, reason=检索阻断原因;</li>
 *     <li>证据不足: 证据为空或条数 &lt; min-evidence-count → answerable=false, reason="证据不足(需至少X条)";</li>
 *     <li>证据冲突: 存在冲突证据且 conflict-block=true → answerable=false, reason="证据存在冲突";</li>
 *     <li>实体不匹配: questionProducts 非空且 entity-consistency=true 且无证据覆盖任一实体 → answerable=false, reason="产品不匹配";</li>
 *     <li>阈值判定: answerable = confidence &gt;= answer-threshold; consultable = confidence &gt;= consult-threshold。</li>
 * </ol>
 * 置信度融合: confidence = Σ(权重 × 指标), 指标含 top-score(最高证据分) / evidence-count(条数占比) /
 * entity-coverage(实体覆盖率), 权重和不为 1.0 时告警并重新归一化, 最终钳制到 0~1。
 * <p>
 * 健壮性: 任何异常 → 降级为 answerable=false + 原因, 绝不抛出。
 */
@Slf4j
@Component
public class SufficiencyJudge {

    /** 权重和容差(浮点比较用) */
    private static final double WEIGHT_SUM_EPSILON = 1e-6;

    /** 默认权重(配置缺失/非法时回退) */
    private static final double DEFAULT_WEIGHT_TOP_SCORE = 0.5;
    private static final double DEFAULT_WEIGHT_EVIDENCE_COUNT = 0.3;
    private static final double DEFAULT_WEIGHT_ENTITY_COVERAGE = 0.2;

    /** 阈值兜底(配置缺失时) */
    private static final double DEFAULT_ANSWER_THRESHOLD = 0.75;
    private static final double DEFAULT_CONSULT_THRESHOLD = 0.5;
    private static final int DEFAULT_MIN_EVIDENCE_COUNT = 2;

    private final EvidenceProperties properties;

    public SufficiencyJudge(EvidenceProperties properties) {
        this.properties = properties;
    }

    /**
     * 充分性判定(检索未阻断场景)
     */
    public Judgement judge(List<Evidence> evidences, List<Conflict> conflicts, List<String> questionProducts) {
        return judge(evidences, conflicts, questionProducts, null);
    }

    /**
     * 充分性判定
     *
     * @param evidences            组装后的证据(按得分降序)
     * @param conflicts            冲突判定器输出(可为空)
     * @param questionProducts     问题涉及的产品/品牌(实体)
     * @param retrievalBlockReason 检索阻断原因(上游品牌一致性门禁); 未阻断传 null
     * @return 判定结果(answerable=false 时 reason 必填)
     */
    public Judgement judge(List<Evidence> evidences, List<Conflict> conflicts,
                           List<String> questionProducts, String retrievalBlockReason) {
        try {
            List<Evidence> evs = evidences != null ? evidences : Collections.emptyList();
            List<Conflict> cfs = conflicts != null ? conflicts : Collections.emptyList();
            List<String> products = questionProducts != null ? questionProducts : Collections.emptyList();
            EvidenceProperties.Sufficiency cfg = properties.getSufficiency();

            // 1. 指标计算(即使不可作答也计算, 供展示)
            double topScore = maxScore(evs);
            int evidenceCount = evs.size();
            int minCount = minEvidenceCount(cfg);
            double countMetric = Math.min((double) evidenceCount / minCount, 1.0);
            double coverageMetric = entityCoverage(evs, products);
            double confidence = fuse(topScore, countMetric, coverageMetric, cfg);

            // 2. 结构化门禁(顺序固定, 原因可组合)
            List<String> reasons = new ArrayList<>(4);
            if (retrievalBlockReason != null && !retrievalBlockReason.isBlank()) {
                reasons.add(retrievalBlockReason);
            }
            if (evidenceCount < minCount) {
                reasons.add("证据不足(需至少" + minCount + "条)");
            }
            if (!cfs.isEmpty() && Boolean.TRUE.equals(cfg.getConflictBlock())) {
                reasons.add("证据存在冲突");
            }
            if (!products.isEmpty() && Boolean.TRUE.equals(cfg.getEntityConsistency()) && coverageMetric <= 0) {
                reasons.add("产品不匹配");
            }
            if (!reasons.isEmpty()) {
                return build(false, confidence, String.join(";", reasons), evidenceCount, cfs.size());
            }

            // 3. 阈值判定(无结构门禁命中时)
            double answerThreshold = answerThreshold(cfg);
            if (confidence >= answerThreshold) {
                return build(true, confidence, null, evidenceCount, cfs.size());
            }
            // 未达可作答阈值: [consult, answer) 区间 → 可转人工; 低于 consult → 证据充分度不足
            double consultThreshold = consultThreshold(cfg);
            String reason = confidence >= consultThreshold ? "证据充分度不足(可转人工)" : "证据充分度不足";
            return build(false, confidence, reason, evidenceCount, cfs.size());
        } catch (Exception e) {
            // 永不抛出: 降级为不可作答
            log.warn("[judge][充分性判定异常, 降级为不可作答: {}]", e.getMessage(), e);
            return build(false, 0.0, "充分性判定异常", evidences != null ? evidences.size() : 0,
                    conflicts != null ? conflicts.size() : 0);
        }
    }

    // ========== 指标 ==========

    /** 最高证据分(0~1, 无证据为 0; score 为 null 按 0 处理) */
    private double maxScore(List<Evidence> evidences) {
        double max = 0.0;
        for (Evidence evidence : evidences) {
            if (evidence != null && evidence.getScore() != null && evidence.getScore() > max) {
                max = evidence.getScore();
            }
        }
        return max;
    }

    /**
     * 实体覆盖率: 1 = 未指定实体或任一证据内容包含任一实体(大小写不敏感子串匹配,
     * 因 Evidence.products 恒为空, 退化为内容包含检查); 否则 0。
     */
    private double entityCoverage(List<Evidence> evidences, List<String> products) {
        if (products.isEmpty()) {
            return 1.0;
        }
        for (Evidence evidence : evidences) {
            if (evidence == null || evidence.getContent() == null || evidence.getContent().isBlank()) {
                continue;
            }
            String content = evidence.getContent().toLowerCase(Locale.ROOT);
            for (String product : products) {
                if (product != null && !product.isBlank() && content.contains(product.toLowerCase(Locale.ROOT))) {
                    return 1.0;
                }
            }
        }
        return 0.0;
    }

    /**
     * 置信度融合: confidence = Σ(权重 × 指标), 权重和不为 1.0 时告警 + 重新归一化, 结果钳制 0~1。
     */
    private double fuse(double topScore, double countMetric, double coverageMetric, EvidenceProperties.Sufficiency cfg) {
        double[] weights = normalizedWeights(cfg);
        double confidence = weights[0] * topScore + weights[1] * countMetric + weights[2] * coverageMetric;
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * 归一化权重: 负权重视为 0; 权重和 &lt;= 0 回退默认权重; 和不为 1.0(容差内)时告警并重新归一化。
     */
    private double[] normalizedWeights(EvidenceProperties.Sufficiency cfg) {
        EvidenceProperties.Weights weights = cfg.getWeights();
        double topScore = weights != null ? weights.getTopScore() : DEFAULT_WEIGHT_TOP_SCORE;
        double evidenceCount = weights != null ? weights.getEvidenceCount() : DEFAULT_WEIGHT_EVIDENCE_COUNT;
        double entityCoverage = weights != null ? weights.getEntityCoverage() : DEFAULT_WEIGHT_ENTITY_COVERAGE;
        // 负权重视为 0
        topScore = Math.max(0.0, topScore);
        evidenceCount = Math.max(0.0, evidenceCount);
        entityCoverage = Math.max(0.0, entityCoverage);
        double sum = topScore + evidenceCount + entityCoverage;
        if (sum <= 0) {
            log.warn("[judge][权重和 {} <= 0, 回退默认权重 {}/{}/{}]", sum,
                    DEFAULT_WEIGHT_TOP_SCORE, DEFAULT_WEIGHT_EVIDENCE_COUNT, DEFAULT_WEIGHT_ENTITY_COVERAGE);
            return new double[]{DEFAULT_WEIGHT_TOP_SCORE, DEFAULT_WEIGHT_EVIDENCE_COUNT, DEFAULT_WEIGHT_ENTITY_COVERAGE};
        }
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_EPSILON) {
            log.warn("[judge][权重和 {} 不为 1.0, 重新归一化为 {}/{}/{}]", sum,
                    topScore / sum, evidenceCount / sum, entityCoverage / sum);
            topScore /= sum;
            evidenceCount /= sum;
            entityCoverage /= sum;
        }
        return new double[]{topScore, evidenceCount, entityCoverage};
    }

    // ========== 配置读取(含兜底, 全部来自 EvidenceProperties) ==========

    private int minEvidenceCount(EvidenceProperties.Sufficiency cfg) {
        Integer value = cfg.getMinEvidenceCount();
        return (value != null && value > 0) ? value : DEFAULT_MIN_EVIDENCE_COUNT;
    }

    private double answerThreshold(EvidenceProperties.Sufficiency cfg) {
        Double value = cfg.getAnswerThreshold();
        return value != null ? value : DEFAULT_ANSWER_THRESHOLD;
    }

    private double consultThreshold(EvidenceProperties.Sufficiency cfg) {
        Double value = cfg.getConsultThreshold();
        return value != null ? value : DEFAULT_CONSULT_THRESHOLD;
    }

    // ========== 结果构造 ==========

    private Judgement build(boolean answerable, double confidence, String reason,
                            int evidenceCount, int conflictCount) {
        double consultThreshold = consultThreshold(properties.getSufficiency());
        return Judgement.builder()
                .answerable(answerable)
                .confidence(confidence)
                .reason(reason)
                .evidenceCount(evidenceCount)
                .conflictCount(conflictCount)
                .consultable(confidence >= consultThreshold)
                .build();
    }

}
