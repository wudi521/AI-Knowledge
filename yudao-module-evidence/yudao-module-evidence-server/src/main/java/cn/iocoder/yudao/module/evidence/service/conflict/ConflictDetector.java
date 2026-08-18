package cn.iocoder.yudao.module.evidence.service.conflict;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Conflict;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceSimilarity;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 冲突判定器: 同主题证据对(规则) + LLM 结构化判定(单次调用) + 解析兜底
 * <p>
 * 流程:
 * <ol>
 *     <li>候选对: 两两计算 {@link EvidenceSimilarity#similarity(String, String)},
 *         相似度 &gt;= 0.5 视为同一事实点候选对(按输入顺序 i&lt;j, 上限 10 对, 超出截断并告警);</li>
 *     <li>LLM 判定: 一次 chat 调用传入全部候选对, 要求输出全部证据对的 conflict true/false;</li>
 *     <li>解析: 提取 JSON(容忍代码围栏/前后缀), 仅返回 conflict=true 的对;</li>
 *     <li>降级: 任何异常/解析失败 → 告警 + 视为无冲突(保守策略), 绝不抛出。</li>
 * </ol>
 * 前提: 入参为去重后、按得分降序的证据列表(索引即位置, 判定结果直接引用该位置)。
 */
@Slf4j
@Component
public class ConflictDetector {

    /** 同主题配对阈值(规格给定常量: 内容字符重叠度 >= 0.5 视为同一事实点候选) */
    private static final double TOPIC_SIMILARITY_THRESHOLD = 0.5;

    /** 单次 LLM 判定的候选对上限(控制 Prompt 长度与调用成本) */
    private static final int MAX_CANDIDATE_PAIRS = 10;

    /** 证据内容截断长度(字) */
    private static final int CONTENT_MAX_LEN = 300;

    /** 系统提示词: 证据一致性审查员, 只输出结构化 JSON */
    private static final String SYSTEM_PROMPT = """
            你是证据一致性审查员。判断下列证据对是否存在矛盾: 即对同一事实点(如价格、期限、政策等)的说法相互冲突。
            只输出 JSON, 不要输出任何其他文字。JSON 格式固定为:
            {"conflicts":[{"pair":[0,1],"conflict":true,"reason":"矛盾原因说明"}]}
            要求:
            1. conflicts 数组必须包含给出的全部证据对, 每对一条记录;
            2. pair 中的两个数字是证据在输入列表中的编号, 与"证据[i]"标签一一对应, 原样回填, 不要改写;
            3. 存在矛盾时 conflict 为 true 并填写 reason(具体说明哪两个说法冲突); 不存在矛盾时 conflict 为 false, reason 给空字符串。
            """;

    @Resource
    private ModelApi modelApi;

    /**
     * 冲突判定
     *
     * @param evidences 去重后、按得分降序的证据列表(索引即位置); 少于 2 条直接返回空
     * @return 冲突列表(仅 conflict=true 的对, 含 LLM 给出的原因); 任何失败/异常 → 空列表, 不抛出
     */
    public List<Conflict> detect(List<Evidence> evidences) {
        if (evidences == null || evidences.size() < 2) {
            return List.of();
        }
        // 1. 同主题候选对(规则, 上限 10)
        List<int[]> pairs = buildCandidatePairs(evidences);
        if (pairs.isEmpty()) {
            return List.of();
        }
        // 2. LLM 结构化判定(一次调用全部候选对; 与检索模块相同 chat 约定: system + user, 服务端关闭思考)
        String resp;
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(buildUserPrompt(evidences, pairs));
            resp = modelApi.chat(req).getCheckedData();
        } catch (Exception e) {
            log.warn("[detect][LLM 冲突判定调用异常, 保守降级为无冲突: {}]", e.getMessage());
            return List.of();
        }
        // 3. 解析(失败/异常 → 无冲突)
        return parseConflicts(resp, pairs);
    }

    /** 同主题候选对: 相似度 >= 0.5 且双方均有实质内容(空白无法构成矛盾), 按输入顺序取前 10 对 */
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
            log.info("[detect][同主题候选对共 {} 对, 截断至前 {} 对]", all.size(), MAX_CANDIDATE_PAIRS);
            return new ArrayList<>(all.subList(0, MAX_CANDIDATE_PAIRS));
        }
        return all;
    }

    /** 组装用户提示词: 逐对给出证据(编号为输入列表位置, 内容截断 300 字) */
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

    /** 截断内容至 300 字(超出加省略号) */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= CONTENT_MAX_LEN) {
            return content;
        }
        return content.substring(0, CONTENT_MAX_LEN) + "…";
    }

    /** 解析 LLM 输出: 提取 JSON → 逐条校验 pair 归属候选对 → 仅保留 conflict=true 的条目 */
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

    /** 解析单条冲突记录: pair 必须命中候选对(容错反转), 仅 conflict=true 且命中时返回 Conflict */
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
            boolean conflict = Boolean.TRUE.equals(entry.getBool("conflict", false));
            if (!conflict) {
                return null;
            }
            return Conflict.builder()
                    .evidenceIndexA(a)
                    .evidenceIndexB(b)
                    .reason(entry.getStr("reason", ""))
                    .build();
        } catch (Exception e) {
            log.warn("[detect][单条冲突记录解析失败, 跳过: {}]", e.getMessage());
            return null;
        }
    }

    /** 候选对是否包含 (a, b)(a &lt; b) */
    private boolean containsPair(List<int[]> pairs, int a, int b) {
        for (int[] pair : pairs) {
            if (pair[0] == a && pair[1] == b) {
                return true;
            }
        }
        return false;
    }

}
