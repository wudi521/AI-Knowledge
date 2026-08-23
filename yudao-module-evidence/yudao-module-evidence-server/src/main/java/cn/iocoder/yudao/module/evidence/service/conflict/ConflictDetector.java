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
 * 通用证据冲突判定器。
 * PATENT 文献默认不使用“客服政策冲突”门禁：不同专利、不同权利要求、不同实施例的差异本身就是正常研究对象，
 * 不能因为文字不同就阻断回答；专利领域的矛盾/对比应由专门的 Patent Evidence Policy 在后续版本实现。
 */
@Slf4j
@Component
public class ConflictDetector {

    private static final double TOPIC_SIMILARITY_THRESHOLD = 0.5;
    private static final int MAX_CANDIDATE_PAIRS = 10;
    private static final int CONTENT_MAX_LEN = 300;
    private static final List<String> NON_CONFLICT_REASON_MARKERS = List.of(
            "无矛盾", "没有矛盾", "不存在矛盾", "无冲突", "没有冲突", "不存在冲突",
            "描述一致", "说法一致", "内容一致", "两者一致", "相同", "一致，无", "一致,无"
    );

    private static final String SYSTEM_PROMPT = """
            你是证据一致性审查员。只判断“同一事实、同一对象、同一范围、同一时间条件下”的两条证据是否给出互不相容的结论。
            仅仅是内容不同、信息互补、一个更详细、来自不同文档或不同实施例，都不算冲突。
            只输出 JSON，不要输出任何其他文字：
            {"conflicts":[{"pair":[0,1],"conflict":true,"reason":"两条证据在同一事实点上的互斥内容"}]}
            要求: conflicts 包含全部证据对；pair 原样回填；只有确实互斥时 conflict=true；
            一致、互补、范围不同或无法判断一律 conflict=false；严禁 true 与“无矛盾/一致”同时出现。
            """;

    @Resource private ModelApi modelApi;
    @Resource private PromptSupport promptSupport;

    public List<Conflict> detect(List<Evidence> evidences) {
        if (evidences == null || evidences.size() < 2) return List.of();
        if (isPatentEvidenceSet(evidences)) {
            log.debug("[detect][PATENT 证据集跳过通用冲突检测, evidenceCount={}]", evidences.size());
            return List.of();
        }
        List<int[]> pairs = buildCandidatePairs(evidences);
        if (pairs.isEmpty()) return List.of();
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("conflict-detect", SYSTEM_PROMPT));
            req.setUser(buildUserPrompt(evidences, pairs));
            return parseConflicts(modelApi.chat(req).getCheckedData(), pairs);
        } catch (Exception e) {
            log.warn("[detect][LLM 冲突判定调用异常, 保守降级为无冲突: {}]", e.getMessage());
            return List.of();
        }
    }

    private boolean isPatentEvidenceSet(List<Evidence> evidences) {
        boolean sawPatent = false;
        for (Evidence evidence : evidences) {
            if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) return false;
            try {
                JSONObject meta = JSONUtil.parseObj(evidence.getChunkMetadata());
                if (!"PATENT".equalsIgnoreCase(meta.getStr("domainCode"))) return false;
                sawPatent = true;
            } catch (Exception e) {
                return false;
            }
        }
        return sawPatent;
    }

    private List<int[]> buildCandidatePairs(List<Evidence> evidences) {
        List<int[]> all = new ArrayList<>();
        for (int i = 0; i < evidences.size() - 1; i++) {
            for (int j = i + 1; j < evidences.size(); j++) {
                String a = evidences.get(i).getContent(), b = evidences.get(j).getContent();
                if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) continue;
                if (EvidenceSimilarity.similarity(a, b) >= TOPIC_SIMILARITY_THRESHOLD) all.add(new int[]{i, j});
            }
        }
        if (all.size() > MAX_CANDIDATE_PAIRS) {
            all.sort((a, b) -> Double.compare(
                    EvidenceSimilarity.similarity(evidences.get(b[0]).getContent(), evidences.get(b[1]).getContent()),
                    EvidenceSimilarity.similarity(evidences.get(a[0]).getContent(), evidences.get(a[1]).getContent())));
            return new ArrayList<>(all.subList(0, MAX_CANDIDATE_PAIRS));
        }
        return all;
    }

    private String buildUserPrompt(List<Evidence> evidences, List<int[]> pairs) {
        StringBuilder sb = new StringBuilder("请审查以下 ").append(pairs.size()).append(" 对证据:\n\n");
        for (int k = 0; k < pairs.size(); k++) {
            int a = pairs.get(k)[0], b = pairs.get(k)[1];
            sb.append("证据对 ").append(k + 1).append(":\n证据[").append(a).append("]: ")
                    .append(truncate(evidences.get(a).getContent())).append("\n证据[").append(b).append("]: ")
                    .append(truncate(evidences.get(b).getContent())).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String content) {
        if (content == null) return "";
        return content.length() <= CONTENT_MAX_LEN ? content : content.substring(0, CONTENT_MAX_LEN) + "…";
    }

    private List<Conflict> parseConflicts(String resp, List<int[]> pairs) {
        try {
            if (StrUtil.isBlank(resp)) return List.of();
            String jsonText = JsonExtract.extractObject(resp);
            if (jsonText == null) return List.of();
            JSONArray conflicts = JSONUtil.parseObj(jsonText).getJSONArray("conflicts");
            if (conflicts == null) return List.of();
            List<Conflict> result = new ArrayList<>();
            for (int i = 0; i < conflicts.size(); i++) {
                Conflict conflict = parseEntry(conflicts.getJSONObject(i), pairs);
                if (conflict != null) result.add(conflict);
            }
            return result;
        } catch (Exception e) {
            log.warn("[detect][LLM 冲突结果解析失败, 视为无冲突: {}]", e.getMessage());
            return List.of();
        }
    }

    private Conflict parseEntry(JSONObject entry, List<int[]> pairs) {
        if (entry == null) return null;
        try {
            JSONArray pairArr = entry.getJSONArray("pair");
            if (pairArr == null || pairArr.size() < 2) return null;
            int a = pairArr.getInt(0), b = pairArr.getInt(1);
            if (a > b) { int t = a; a = b; b = t; }
            if (!containsPair(pairs, a, b) || !Boolean.TRUE.equals(entry.getBool("conflict", false))) return null;
            String reason = StrUtil.nullToEmpty(entry.getStr("reason")).trim();
            if (StrUtil.isBlank(reason) || isSelfContradictoryConflictReason(reason)) {
                log.warn("[detect][自相矛盾/无原因的 conflict=true 被忽略: pair=[{},{}], reason={}]", a, b, reason);
                return null;
            }
            return Conflict.builder().evidenceIndexA(a).evidenceIndexB(b).reason(reason).build();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSelfContradictoryConflictReason(String reason) {
        String normalized = StrUtil.nullToEmpty(reason).toLowerCase(Locale.ROOT).replace(" ", "");
        for (String marker : NON_CONFLICT_REASON_MARKERS) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT).replace(" ", ""))) return true;
        }
        return false;
    }

    private boolean containsPair(List<int[]> pairs, int a, int b) {
        for (int[] pair : pairs) if (pair[0] == a && pair[1] == b) return true;
        return false;
    }
}
