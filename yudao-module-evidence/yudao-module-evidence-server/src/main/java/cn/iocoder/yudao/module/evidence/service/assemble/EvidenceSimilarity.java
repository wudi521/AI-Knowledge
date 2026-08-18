package cn.iocoder.yudao.module.evidence.service.assemble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 证据相似度与分数归一化(纯静态工具方法, 无外部依赖, 便于单元测试)
 */
public final class EvidenceSimilarity {

    private EvidenceSimilarity() {
    }

    /**
     * 文本相似度: 字符多重集交集重合度(基于归一化文本)
     * <p>
     * sim = 2 * |公共字符数| / (lenA + lenB)
     * 公共字符数按 Map&lt;Character,Integer&gt; 取两文本字符计数的最小值累加(即多重集交集大小)。
     * 两文本均为空视为相同(1.0), 仅一方为空视为完全不同(0.0)。
     *
     * @param a 文本 A
     * @param b 文本 B
     * @return 0~1 的相似度
     */
    public static double similarity(String a, String b) {
        String na = normalizeText(a);
        String nb = normalizeText(b);
        if (na.isEmpty() && nb.isEmpty()) {
            return 1.0;
        }
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> countA = charCounts(na);
        Map<Character, Integer> countB = charCounts(nb);
        int common = 0;
        for (Map.Entry<Character, Integer> entry : countA.entrySet()) {
            Integer countBOfChar = countB.get(entry.getKey());
            if (countBOfChar != null) {
                common += Math.min(entry.getValue(), countBOfChar);
            }
        }
        return 2.0 * common / (na.length() + nb.length());
    }

    /**
     * 文本归一化: 去除全部空白字符 + 转小写(中文不受影响, 英文大小写不敏感)
     */
    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * min-max 归一化到 0~1(批次内):
     * <ul>
     *     <li>负值先钳制为 0, null 按 0 处理;</li>
     *     <li>全部相等(含全部缺失/全部为 0)时返回全 1.0, 避免除零;</li>
     *     <li>返回列表与入参一一对应。</li>
     * </ul>
     */
    public static List<Double> minMaxNormalize(List<Double> rawScores) {
        if (rawScores == null || rawScores.isEmpty()) {
            return Collections.emptyList();
        }
        // 1. 负值钳制为 0(null 按 0)
        List<Double> clamped = new ArrayList<>(rawScores.size());
        for (Double raw : rawScores) {
            clamped.add(raw == null ? 0.0 : Math.max(raw, 0.0));
        }
        // 2. 全相等 → 无法区分度, 按 1.0 处理
        double min = Collections.min(clamped);
        double max = Collections.max(clamped);
        if (max - min < 1e-9) {
            return Collections.nCopies(clamped.size(), 1.0);
        }
        // 3. 标准 min-max
        List<Double> normalized = new ArrayList<>(clamped.size());
        for (double value : clamped) {
            normalized.add((value - min) / (max - min));
        }
        return normalized;
    }

    /** 字符多重集计数 */
    private static Map<Character, Integer> charCounts(String text) {
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < text.length(); i++) {
            counts.merge(text.charAt(i), 1, Integer::sum);
        }
        return counts;
    }

}
