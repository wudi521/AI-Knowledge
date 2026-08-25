package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PATENT 精确权利要求的确定性回答器(RAW 原文 / DEPENDENCY 依赖关系; SUMMARY 走受约束 LLM)。 */
public final class PatentExactClaimAnswerer {

    private static final Pattern CLAIM_NO = Pattern.compile("权利要求\\s*(\\d+)");

    private PatentExactClaimAnswerer() {}

    /**
     * RAW(原文): 直接返回目标 Claim 的原文, 0 LLM / 0 Vector。
     * DEPENDENCY(引用依赖): 读结构化 dependsOn, 保守表述, 0 LLM / 0 Vector。
     */
    public static DirectAnswer tryAnswer(String query, List<Evidence> evidences) {
        if (StrUtil.isBlank(query) || evidences == null || evidences.size() != 1) return null;

        Matcher matcher = CLAIM_NO.matcher(query);
        if (!matcher.find()) return null;
        int requestedClaimNo = Integer.parseInt(matcher.group(1));

        Evidence evidence = evidences.get(0);
        if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) return null;
        try {
            JSONObject meta = JSONUtil.parseObj(evidence.getChunkMetadata());
            if (!"PATENT".equalsIgnoreCase(meta.getStr("domainCode"))) return null;
            if (!"CLAIMS".equalsIgnoreCase(meta.getStr("sectionType"))) return null;
            Integer claimNo = meta.getInt("claimNo");
            if (claimNo == null || claimNo != requestedClaimNo) return null;

            if (containsAny(query, "原文", "条文", "具体内容是什么", "内容是什么", "写了什么")) {
                String content = StrUtil.trim(evidence.getContent());
                if (StrUtil.isBlank(content)) return null;
                return new DirectAnswer("权利要求" + claimNo + "原文是：“" + content + "”。[C1]", 0);
            }

            if (!containsAny(query, "引用", "依赖", "从属", "在先权利要求", "根据权利要求")) return null;
            JSONArray depends = meta.getJSONArray("dependsOn");
            List<Integer> dependsOn = new ArrayList<>();
            if (depends != null) {
                for (Object value : depends) {
                    if (value instanceof Number n) dependsOn.add(n.intValue());
                    else if (value != null) dependsOn.add(Integer.parseInt(value.toString()));
                }
            }

            String answer;
            if (dependsOn.isEmpty()) {
                answer = "权利要求" + claimNo + "未引用其他在先权利要求，属于独立权利要求。[C1]";
            } else {
                answer = "权利要求" + claimNo + "引用的在先权利要求包括"
                        + String.join("、", dependsOn.stream().map(String::valueOf).toList()) + "。[C1]";
            }
            return new DirectAnswer(answer, 0);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    public record DirectAnswer(String answer, int evidenceIndex) {}
}
