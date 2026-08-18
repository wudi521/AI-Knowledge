package cn.iocoder.yudao.module.evidence.service.assemble;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 证据去重器: 基于字符重叠相似度合并重复证据(通道取并集, 保留高分证据)
 * <p>
 * 前提: 入参按得分降序(证据组装器输出), 因此 "首个 = 最高分", 得分相同时保留先出现的证据。
 * 复杂度 O(n²), 仅用于小批量(≤ 8 条)证据, 可接受。
 */
@Slf4j
@Component
public class EvidenceDeduplicator {

    /**
     * 相似度阈值: 相似度 &gt;= 阈值即视为重复并合并(配置驱动, 默认 0.85)
     */
    @Value("${yudao.evidence.dedup.similarity-threshold:0.85}")
    private double similarityThreshold;

    /**
     * 去重: 相似证据合并为一条(保留更高分者, 通道并集), 其余原样保留
     *
     * @param evidences 输入证据(建议按得分降序, 不满足时按分数择优保留)
     * @return 去重结果(保持输入相对顺序)
     */
    public DedupResult dedupe(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return new DedupResult(new ArrayList<>(), 0);
        }
        List<Evidence> deduped = new ArrayList<>(evidences.size());
        int removedCount = 0;
        for (Evidence candidate : evidences) {
            // 与已保留证据逐一比对(不会与自身比对)
            Evidence duplicated = null;
            for (Evidence kept : deduped) {
                if (EvidenceSimilarity.similarity(kept.getContent(), candidate.getContent()) >= similarityThreshold) {
                    duplicated = kept;
                    break;
                }
            }
            if (duplicated != null) {
                merge(duplicated, candidate);
                removedCount++;
            } else {
                deduped.add(candidate);
            }
        }
        if (removedCount > 0) {
            log.info("[dedupe][共 {} 条证据, 合并重复 {} 条, 去重后 {} 条, 阈值 {}]",
                    evidences.size(), removedCount, deduped.size(), similarityThreshold);
        }
        return new DedupResult(deduped, removedCount);
    }

    /**
     * 合并: 保留更高分证据(输入按分降序时首个即最高分, 此处仍做兜底比较),
     * 通道取并集并按原顺序去重。
     */
    private void merge(Evidence keep, Evidence removed) {
        // 1. 分数: 保留更高者(兜底: 入参未按分排序时仍正确)
        if (removed.getScore() != null && (keep.getScore() == null || removed.getScore() > keep.getScore())) {
            keep.setScore(removed.getScore());
        }
        // 2. 通道并集(去重, 保持出现顺序)
        Set<String> mergedChannels = new LinkedHashSet<>();
        if (keep.getChannels() != null) {
            mergedChannels.addAll(keep.getChannels());
        }
        if (removed.getChannels() != null) {
            mergedChannels.addAll(removed.getChannels());
        }
        keep.setChannels(new ArrayList<>(mergedChannels));
    }

}
