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

/** PATENT 精确权利要求的确定性回答器。 */
final class PatentExactClaimAnswerer {

    private static final Pattern CLAIM_NO = Pattern.compile("权利要求\\s*(\\d+)");

    private PatentExactClaimAnswerer() {}

    /**
     * 当前只对“依赖/引用关系”做确定性回答，因为 dependsOn 是 ingestion 阶段规则解析出的结构化事实。
     * “主要限定什么/核心组成”仍交给 LLM 对单条精确 Claim 做概括，避免程序硬编码自然语言摘要。
     */
    static DirectAnswer tryAnswer(String query, List<Evidence> evidences) {
        if (StrUtil.isBlank(query) || evidences == null || evidences.size() != 1) return null;
        if (!containsAny(query, "引用", "依赖", "从属", "在先权利要求", "根据权利要求")) return null;

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
            } else if (isContinuous(dependsOn)) {
                answer = "权利要求" + claimNo + "引用了权利要求" + dependsOn.get(0)
                        + "至" + dependsOn.get(dependsOn.size() - 1) + "中的任意一项。[C1]";
            } else {
                answer = "权利要求" + claimNo + "引用了权利要求"
                        + String.join("、", dependsOn.stream().map(String::valueOf).toList()) + "。[C1]";
            }
            return new DirectAnswer(answer, 0);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean isContinuous(List<Integer> values) {
        if (values.size() < 2) return false;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) != values.get(i - 1) + 1) return false;
        }
        return true;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    record DirectAnswer(String answer, int evidenceIndex) {}
}
