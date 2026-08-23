package cn.iocoder.yudao.module.evidence.service.sufficiency;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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

/** 证据充分性判定器。 */
@Slf4j
@Component
public class SufficiencyJudge {

    private static final double WEIGHT_SUM_EPSILON = 1e-6;
    private static final double DEFAULT_WEIGHT_TOP_SCORE = 0.5;
    private static final double DEFAULT_WEIGHT_EVIDENCE_COUNT = 0.3;
    private static final double DEFAULT_WEIGHT_ENTITY_COVERAGE = 0.2;
    private static final double DEFAULT_ANSWER_THRESHOLD = 0.75;
    private static final double DEFAULT_CONSULT_THRESHOLD = 0.5;
    private static final int DEFAULT_MIN_EVIDENCE_COUNT = 2;

    private final EvidenceProperties properties;

    public SufficiencyJudge(EvidenceProperties properties) {
        this.properties = properties;
    }

    public Judgement judge(List<Evidence> evidences, List<Conflict> conflicts, List<String> questionProducts) {
        return judge(evidences, conflicts, questionProducts, null);
    }

    public Judgement judge(List<Evidence> evidences, List<Conflict> conflicts,
                           List<String> questionProducts, String retrievalBlockReason) {
        try {
            List<Evidence> evs = evidences != null ? evidences : Collections.emptyList();
            List<Conflict> cfs = conflicts != null ? conflicts : Collections.emptyList();
            List<String> products = questionProducts != null ? questionProducts : Collections.emptyList();
            EvidenceProperties.Sufficiency cfg = properties.getSufficiency();

            double topScore = maxScore(evs);
            int evidenceCount = evs.size();
            int minCount = effectiveMinEvidenceCount(evs, cfg);
            double countMetric = countMetric(evidenceCount, minCount);
            double coverageMetric = entityCoverage(evs, products);
            double confidence = fuse(topScore, countMetric, coverageMetric, cfg);

            List<String> reasons = new ArrayList<>(4);
            if (StrUtil.isNotBlank(retrievalBlockReason)) {
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

            // 专利中的著录信息、单项权利要求属于权威原文；命中一条高质量证据即可进入生成/验证，
            // 不应为了凑“至少2条”引入其他专利造成污染。
            if (isAuthoritativePatentEvidence(evs) && evidenceCount >= 1) {
                return build(true, Math.max(confidence, answerThreshold(cfg)), null, evidenceCount, cfs.size());
            }

            double answerThreshold = answerThreshold(cfg);
            if (confidence >= answerThreshold) {
                return build(true, confidence, null, evidenceCount, cfs.size());
            }
            double consultThreshold = consultThreshold(cfg);
            String reason = confidence >= consultThreshold ? "证据充分度不足(可转人工)" : "证据充分度不足";
            return build(false, confidence, reason, evidenceCount, cfs.size());
        } catch (Exception e) {
            log.warn("[judge][充分性判定异常, 降级为不可作答: {}]", e.getMessage(), e);
            return build(false, 0.0, "充分性判定异常", evidences != null ? evidences.size() : 0,
                    conflicts != null ? conflicts.size() : 0);
        }
    }

    private int effectiveMinEvidenceCount(List<Evidence> evidences, EvidenceProperties.Sufficiency cfg) {
        return isAuthoritativePatentEvidence(evidences) ? 1 : minEvidenceCount(cfg);
    }

    private boolean isAuthoritativePatentEvidence(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return false;
        }
        for (Evidence evidence : evidences) {
            if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) {
                continue;
            }
            try {
                JSONObject meta = JSONUtil.parseObj(evidence.getChunkMetadata());
                if (!"PATENT".equalsIgnoreCase(meta.getStr("domainCode"))) {
                    continue;
                }
                String section = meta.getStr("sectionType");
                if ("CLAIMS".equalsIgnoreCase(section) || "BIBLIOGRAPHIC".equalsIgnoreCase(section)) {
                    return true;
                }
            } catch (Exception ignore) {
                // 非法 metadata 不改变通用判定
            }
        }
        return false;
    }

    private double maxScore(List<Evidence> evidences) {
        double max = 0.0;
        for (Evidence evidence : evidences) {
            if (evidence == null) continue;
            Double s = evidence.getRawScore() != null ? evidence.getRawScore() : evidence.getScore();
            if (s != null && s > max) max = s;
        }
        return max;
    }

    private double countMetric(int evidenceCount, int minCount) {
        if (evidenceCount <= 0) return 0.0;
        if (evidenceCount >= minCount) return Math.min(1.0, 0.7 + 0.15 * (evidenceCount - minCount));
        double ratio = (double) evidenceCount / minCount;
        return Math.min(0.65, ratio * 0.65);
    }

    private double entityCoverage(List<Evidence> evidences, List<String> products) {
        if (products.isEmpty()) return 1.0;
        for (Evidence evidence : evidences) {
            if (evidence == null || StrUtil.isBlank(evidence.getContent())) continue;
            String content = evidence.getContent().toLowerCase(Locale.ROOT);
            for (String product : products) {
                if (StrUtil.isNotBlank(product) && content.contains(product.toLowerCase(Locale.ROOT))) return 1.0;
            }
        }
        return 0.0;
    }

    private double fuse(double topScore, double countMetric, double coverageMetric, EvidenceProperties.Sufficiency cfg) {
        double[] weights = normalizedWeights(cfg);
        double confidence = weights[0] * topScore + weights[1] * countMetric + weights[2] * coverageMetric;
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private double[] normalizedWeights(EvidenceProperties.Sufficiency cfg) {
        EvidenceProperties.Weights weights = cfg.getWeights();
        double topScore = weights != null ? weights.getTopScore() : DEFAULT_WEIGHT_TOP_SCORE;
        double evidenceCount = weights != null ? weights.getEvidenceCount() : DEFAULT_WEIGHT_EVIDENCE_COUNT;
        double entityCoverage = weights != null ? weights.getEntityCoverage() : DEFAULT_WEIGHT_ENTITY_COVERAGE;
        topScore = Math.max(0.0, topScore);
        evidenceCount = Math.max(0.0, evidenceCount);
        entityCoverage = Math.max(0.0, entityCoverage);
        double sum = topScore + evidenceCount + entityCoverage;
        if (sum <= 0) {
            return new double[]{DEFAULT_WEIGHT_TOP_SCORE, DEFAULT_WEIGHT_EVIDENCE_COUNT, DEFAULT_WEIGHT_ENTITY_COVERAGE};
        }
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_EPSILON) {
            topScore /= sum;
            evidenceCount /= sum;
            entityCoverage /= sum;
        }
        return new double[]{topScore, evidenceCount, entityCoverage};
    }

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

    private Judgement build(boolean answerable, double confidence, String reason,
                            int evidenceCount, int conflictCount) {
        return Judgement.builder()
                .answerable(answerable)
                .confidence(confidence)
                .reason(reason)
                .evidenceCount(evidenceCount)
                .conflictCount(conflictCount)
                .consultable(confidence >= consultThreshold(properties.getSufficiency()))
                .build();
    }
}
