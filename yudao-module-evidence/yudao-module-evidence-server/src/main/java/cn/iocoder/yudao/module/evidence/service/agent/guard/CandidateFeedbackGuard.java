package cn.iocoder.yudao.module.evidence.service.agent.guard;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 防止检索候选反向污染用户目标。
 * 候选标题只能作为 observation/evidence，不能在没有原始问题依据时被升级成下一轮硬查询锚点。
 */
@Component
public class CandidateFeedbackGuard {
    private static final int MIN_CJK_ANCHOR = 4;

    public List<String> retainSafeQueries(String originalGoal, List<String> proposedQueries, List<Evidence> candidates) {
        if (proposedQueries == null || proposedQueries.isEmpty()) return List.of();
        Set<String> forbidden = candidateOnlyAnchors(originalGoal, candidates);
        List<String> safe = new ArrayList<>();
        for (String proposed : proposedQueries) {
            if (StrUtil.isBlank(proposed)) continue;
            String normalized = normalize(proposed);
            boolean contaminated = forbidden.stream().anyMatch(normalized::contains);
            if (!contaminated && !safe.contains(proposed.trim())) safe.add(proposed.trim());
        }
        return List.copyOf(safe);
    }

    Set<String> candidateOnlyAnchors(String originalGoal, List<Evidence> candidates) {
        String goal = normalize(originalGoal);
        Set<String> anchors = new LinkedHashSet<>();
        if (candidates == null) return anchors;
        for (Evidence evidence : candidates) {
            if (evidence == null || StrUtil.isBlank(evidence.getDocumentName())) continue;
            String title = normalize(evidence.getDocumentName());
            if (title.length() < MIN_CJK_ANCHOR) continue;
            if (!goal.contains(title)) anchors.add(title);
            int n = Math.min(6, title.length());
            if (n >= MIN_CJK_ANCHOR) {
                for (int i = 0; i + n <= title.length(); i++) {
                    String gram = title.substring(i, i + n);
                    if (!goal.contains(gram)) anchors.add(gram);
                }
            }
        }
        return anchors;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
