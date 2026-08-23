package cn.iocoder.yudao.module.evidence.service.conflict;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceSimilarity;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 冲突判定器: 同主题证据对(规则) + LLM 结构化判定 + 程序级一致性校验。
 */
@Slf4j
@Component
public class ConflictDetector {

    private static final double TOPIC_SIMILARITY_THRESHOLD = 0.5;
    private static final int MAX_CANDIDATE_PAIRS = 10;
    private static final int CONTENT_MAX_LEN = 300;

    /** LLM 错把“一致/无冲突”标为 conflict=true 时的保护词。 */
    private static final List<String> NON_CONFLICT_REASON_MARKERS = List.of(
            "无矛盾", "没有矛盾", "不存在矛盾", "无冲突", "没有冲突", "不存在冲突",
            "描述一致", "说法一致", "内容一致", "两者一致", "相同", "一致，无", "一致,无"
    );

    private static final String SYSTEM_PROMPT = """
            你是证据一致性审查员。只判断“同一事实、同一对象、同一范围、同一时间条件下”的两条证据是否给出互不相容的结论。
            仅仅是内容不同、信息互补、一个更详细、来自不同文档或不同实施例，都不算冲突。
            只输出 JSON，不要输出任何其他文字：
            {"conflicts":[{"pair":[0,1],"conflict":true,"reason":"两条证据在同一事实点上的互斥内容"}]}
            要求:
            1. conflicts 数组必须包含给出的全部证据对；
            2. pair 原样回填证据编号；
            3. 只有确实互斥时 conflict=true；
            4. 若两条证据一致、相同、互补、范围不同或无法判断，conflict=false 且 reason=""；
            5. 严禁出现 conflict=true 但 reason 又写“无矛盾/一致/不冲突”的自相矛盾结果。
            """;

    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;

    public List<Conflict> detect(List<Evidence> evidences) {
        if (evidences == null || evidences.size() < 2) {
            return List.of();
        }
        List<int[]> pairs = buildCandidatePairs(evidences);
        if (pairs.isEmpty()) {
            return List.of();
        }
        String resp;
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("conflict-detect", SYSTEM_PROMPT));
            req.setUser(buildUserPrompt(evidences, pairs));
            resp = modelApi.chat(req).getCheckedData();
        } catch (Exception e) {
            log.warn("[detect][LLM 冲突判定调用异常, 保守降级为无冲突: {}]", e.getMessage());
            return List.of();
        }
        return parseConflicts(resp, pairs);
    }

    private List<int[]> buildCandidatePairs(List<Evidence> evidences) {
        List<int[]> all = new ArrayList<>();
        for (int i = 0; i < evidences.size() - 1; i++) {
            for (int j = i + 1; j < evidences.size(); j++) {
                String contentA = evidences.get(i).getContent();
                String contentB = evidences.get(j).getContent();
                if (StrUtil.isBlank(contentA) || StrUtil.isBlank(contentB)) {
                    continue;
                }
                if (EvidenceSimilarity.similarity(contentA, contentB) >= TOPIC_SIMILARITY_THRESHOLD) {
                    all.add(new int[]{i, j});
                }
            }
        }
        if (all.size() > MAX_CANDIDATE_PAIRS) {
            all.sort((a, b) -> Double.compare(
                    EvidenceSimilarity.similarity(evidences.get(b[0]).getContent(), evidences.get(b[1]).getContent()),
                    EvidenceSimilarity.similarity(evidences.get(a[0]).getContent(), evidences.get(a[1]).getContent())));
            log.warn("[detect][同主题候选对共 {} 对, 按相似度降序截断至前 {} 对]", all.size(), MAX_CANDIDATE_PAIRS);
            return new ArrayList<>(all.subList(0, MAX_CANDIDATE_PAIRS));
        }
        return all;
    }

    private String buildUserPrompt(List<Evidence> evidences, List<int[]> pairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("请审查以下 ").append(pairs.size()).append(" 对证据:\n\n");
        for (int k = 0; k < pairs.size(); k++) {
            int a = pairs.get(k)[0];
            int b = pairs.get(k)[1];
            sb.append("证据对 ").append(k + 1).append(":\n");
            sb.append("证据[").append(a).append("]: ").append(truncate(evidences.get(a).getContent())).append('\n');
            sb.append("证据[").append(b).append("]: ").append(truncate(evidences.get(b).getContent())).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= CONTENT_MAX_LEN) {
            return content;
        }
        return content.substring(0, CONTENT_MAX_LEN) + "…";
    }

    private List<Conflict> parseConflicts(String resp, List<int[]> pairs) {
        try {
            if (StrUtil.isBlank(resp)) {
                log.warn("[detect][LLM 响应为空, 视为无冲突]");
                return List.of();
            }
            String jsonText = JsonExtract.extractObject(resp);
            if (jsonText == null) {
                log.warn("[detect][LLM 响应未包含 JSON 对象, 视为无冲突: {}]", StrUtil.maxLength(resp, 200));
                return List.of();
            }
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray conflicts = root.getJSONArray("conflicts");
            if (conflicts == null) {
                log.warn("[detect][LLM 响应缺少 conflicts 字段, 视为无冲突]");
                return List.of();
            }
            List<Conflict> result = new ArrayList<>();
            for (int k = 0; k < conflicts.size(); k++) {
                Conflict conflict = parseEntry(conflicts.getJSONObject(k), pairs);
                if (conflict != null) {
                    result.add(conflict);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[detect][LLM 冲突结果解析失败, 保守降级为无冲突: {}]", e.getMessage());
            return List.of();
        }
    }

    private Conflict parseEntry(JSONObject entry, List<int[]> pairs) {
        if (entry == null) {
            return null;
        }
        try {
            JSONArray pairArr = entry.getJSONArray("pair");
            if (pairArr == null || pairArr.size() < 2) {
                return null;
            }
            int a = pairArr.getInt(0);
            int b = pairArr.getInt(1);
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            if (!containsPair(pairs, a, b)) {
                log.warn("[detect][LLM 返回的 pair [{},{}] 不在候选对中, 跳过]", a, b);
                return null;
            }
            if (!Boolean.TRUE.equals(entry.getBool("conflict", false))) {
                return null;
            }

            String reason = StrUtil.nullToEmpty(entry.getStr("reason")).trim();
            if (isSelfContradictoryConflictReason(reason)) {
                log.warn("[detect][LLM 冲突判定自相矛盾, 降级为无冲突: pair=[{},{}], reason={}]", a, b, reason);
                return null;
            }
            if (StrUtil.isBlank(reason)) {
                log.warn("[detect][LLM conflict=true 但缺少原因, 降级为无冲突: pair=[{},{}]]", a, b);
                return null;
            }

            return Conflict.builder()
                    .evidenceIndexA(a)
                    .evidenceIndexB(b)
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            log.warn("[detect][单条冲突记录解析失败, 跳过: {}]", e.getMessage());
            return null;
        }
    }

    private boolean isSelfContradictoryConflictReason(String reason) {
        if (StrUtil.isBlank(reason)) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String marker : NON_CONFLICT_REASON_MARKERS) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT).replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPair(List<int[]> pairs, int a, int b) {
        for (int[] pair : pairs) {
            if (pair[0] == a && pair[1] == b) {
                return true;
            }
        }
        return false;
    }
}
