package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 有界 Agentic Retrieval 的一次反馈判断。
 *
 * <p>只允许在第一轮检索后调用一次，决定 ACCEPT / REFINE / NARROW / BROADEN / ABSTAIN。
 * 不生成答案，不暴露隐藏思维，只返回可审计决策与下一轮有限查询。</p>
 */
@Slf4j
@Component
public class RetrievalRefinementService {

    private static final String DEFAULT_PROMPT = """
            你是企业知识检索的 Retrieval Judge。你不回答用户问题，只判断当前候选实体是否足以支持 Selection。
            只输出 JSON，不要 Markdown，不要推理过程。
            decision 只能是 ACCEPT/REFINE/NARROW/BROADEN/ABSTAIN。
            - ACCEPT：当前候选已足够相关，可以结束实体选择。
            - REFINE：候选有价值但表达还不够精准，基于已看到的真实候选术语生成更精准查询。
            - NARROW：候选较多但存在明显高相关对象，可在 candidateDocumentIds 中缩小后做第二轮验证。
            - BROADEN：结果过少/为零或原查询过窄，需要保留原意扩大表达。
            - ABSTAIN：当前结果明显不能支持用户 Selection，且二次搜索也不值得继续。
            nextQueries 最多 5 个；不得创造知识库中没有依据的具体事实；只能改善检索表达。
            selectedDocumentIds 只能从 candidateDocumentIds 中选择。
            JSON: {"decision":"ACCEPT","nextQueries":[],"selectedDocumentIds":[],"reasonCode":"SUFFICIENT"}
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;

    public RetrievalRefinementService(ModelApi modelApi, PromptSupport promptSupport) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
    }

    public Decision decide(String originalQuery, List<Evidence> candidates, String traceId) {
        List<Long> candidateDocumentIds = documentIds(candidates);
        if (candidateDocumentIds.isEmpty()) {
            // 空结果值得做一次 BROADEN，由 Planner 原始语义 + Judge 生成第二轮表达。
        }
        long start = System.currentTimeMillis();
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("retrieval-judge-v3", DEFAULT_PROMPT));
            req.setUser(buildInput(originalQuery, candidates, candidateDocumentIds));
            req.setTemperature(0D);
            req.setScenario("retrieval-judge-v3");
            req.setTraceId(traceId);
            CommonResult<String> response = modelApi.chat(req);
            JSONObject json = parseJson(response == null ? null : response.getCheckedData());
            if (json == null) return fallback(candidates, System.currentTimeMillis() - start, "JUDGE_PARSE_FAILED");
            DecisionType type = enumValue(json.getStr("decision"));
            if (type == null) return fallback(candidates, System.currentTimeMillis() - start, "JUDGE_INVALID_DECISION");
            List<String> nextQueries = stringList(json.get("nextQueries"), 5);
            List<Long> selected = longList(json.get("selectedDocumentIds"), candidateDocumentIds);
            if ((type == DecisionType.REFINE || type == DecisionType.BROADEN) && nextQueries.isEmpty()) {
                return fallback(candidates, System.currentTimeMillis() - start, "JUDGE_MISSING_NEXT_QUERY");
            }
            if (type == DecisionType.NARROW && selected.isEmpty()) {
                return fallback(candidates, System.currentTimeMillis() - start, "JUDGE_MISSING_NARROW_SCOPE");
            }
            return new Decision(type, nextQueries, selected,
                    StrUtil.blankToDefault(json.getStr("reasonCode"), type.name()),
                    System.currentTimeMillis() - start, "LLM");
        } catch (Exception e) {
            log.warn("[retrieval-judge-v3][failed: {}]", e.getMessage());
            return fallback(candidates, System.currentTimeMillis() - start, "JUDGE_UNAVAILABLE");
        }
    }

    private Decision fallback(List<Evidence> candidates, long elapsed, String reason) {
        // Judge 故障不把一个已有候选的正常查询打死；有结果时接受当前结果，无结果时拒绝。
        if (candidates != null && !candidates.isEmpty()) {
            return new Decision(DecisionType.ACCEPT, List.of(), List.of(), reason, elapsed, "FALLBACK");
        }
        return new Decision(DecisionType.ABSTAIN, List.of(), List.of(), reason, elapsed, "FALLBACK");
    }

    private String buildInput(String query, List<Evidence> candidates, List<Long> documentIds) {
        StringBuilder sb = new StringBuilder(2200);
        sb.append("selectionQuery=").append(query).append('\n');
        sb.append("candidateDocumentIds=").append(documentIds).append('\n');
        sb.append("candidates:\n");
        if (candidates != null) {
            int i = 0;
            for (Evidence e : candidates) {
                if (e == null || i >= 8) break;
                sb.append('[').append(i++).append("] documentId=").append(e.getDocumentId())
                        .append(" name=").append(StrUtil.maxLength(StrUtil.nullToEmpty(e.getDocumentName()), 100))
                        .append(" score=").append(e.getScore())
                        .append(" text=").append(StrUtil.maxLength(StrUtil.nullToEmpty(e.getContent()).replace('\n', ' '), 260))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private List<Long> documentIds(List<Evidence> evidences) {
        Set<Long> ids = new LinkedHashSet<>();
        if (evidences != null) {
            for (Evidence e : evidences) {
                if (e == null || StrUtil.isBlank(e.getDocumentId())) continue;
                try { ids.add(Long.parseLong(e.getDocumentId())); } catch (Exception ignore) { }
            }
        }
        return new ArrayList<>(ids);
    }

    private JSONObject parseJson(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            return start >= 0 && end > start ? JSONUtil.parseObj(raw.substring(start, end + 1)) : null;
        } catch (Exception e) { return null; }
    }

    private List<String> stringList(Object raw, int limit) {
        if (!(raw instanceof JSONArray array)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : array) {
            String text = item == null ? null : String.valueOf(item).trim();
            if (StrUtil.isNotBlank(text) && !out.contains(text)) out.add(text);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private List<Long> longList(Object raw, List<Long> allowed) {
        if (!(raw instanceof JSONArray array)) return List.of();
        Set<Long> allow = new LinkedHashSet<>(allowed == null ? List.of() : allowed);
        List<Long> out = new ArrayList<>();
        for (Object item : array) {
            try {
                Long id = item instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(item));
                if (allow.contains(id) && !out.contains(id)) out.add(id);
            } catch (Exception ignore) { }
        }
        return out;
    }

    private DecisionType enumValue(String value) {
        if (StrUtil.isBlank(value)) return null;
        try { return DecisionType.valueOf(value.trim().toUpperCase()); } catch (Exception e) { return null; }
    }

    public enum DecisionType { ACCEPT, REFINE, NARROW, BROADEN, ABSTAIN }

    public record Decision(DecisionType type, List<String> nextQueries, List<Long> selectedDocumentIds,
                           String reasonCode, long elapsedMs, String source) { }
}
