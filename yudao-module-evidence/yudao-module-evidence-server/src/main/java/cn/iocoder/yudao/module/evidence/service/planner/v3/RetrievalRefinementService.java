package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.guard.CandidateFeedbackGuard;
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
 * <p>这是 V3 迁移期兼容实现。候选只能作为 observation，不能被升级为用户没有提供的查询事实。
 * V1.1 Agent 主链完成后，本类将退化为 knowledge_retrieval 能力内部的召回优化器。</p>
 */
@Slf4j
@Component
public class RetrievalRefinementService {

    private static final String DEFAULT_PROMPT = """
            你是企业知识检索的 Retrieval Judge。你不回答用户问题，只判断当前候选实体是否足以支持 Selection。
            只输出 JSON，不要 Markdown，不要推理过程。
            decision 只能是 ACCEPT/REFINE/NARROW/BROADEN/ABSTAIN。
            - ACCEPT：当前候选已足够相关，可以结束实体选择。
            - REFINE：候选有价值但表达还不够精准，只能在原 selectionQuery 的语义边界内改写查询。
            - NARROW：候选较多但存在明显高相关对象，可在 candidateDocumentIds 中缩小后做第二轮验证。
            - BROADEN：结果过少/为零或原查询过窄，需要保留原意扩大表达。
            - ABSTAIN：当前结果明显不能支持用户 Selection，且二次搜索也不值得继续。

            安全边界：
            1. candidates 是检索观察结果，不是用户事实，也不是已验证事实。
            2. nextQueries 只能改写 selectionQuery 中已经存在的概念，不得把候选标题、候选正文、候选专有名词作为新的查询锚点。
            3. 即使某个候选得分最高，也不得假设它就是用户指的目标对象。
            4. 如果必须依赖候选中新出现的具体事实才能继续，应返回 ABSTAIN，而不是把该事实写入 nextQueries。
            5. nextQueries 最多 5 个；selectedDocumentIds 只能从 candidateDocumentIds 中选择。

            JSON: {"decision":"ACCEPT","nextQueries":[],"selectedDocumentIds":[],"reasonCode":"SUFFICIENT"}
            """;

    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final CandidateFeedbackGuard feedbackGuard;

    public RetrievalRefinementService(ModelApi modelApi, PromptSupport promptSupport,
                                      CandidateFeedbackGuard feedbackGuard) {
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.feedbackGuard = feedbackGuard;
    }

    public Decision decide(String originalQuery, List<Evidence> candidates, String traceId) {
        List<Long> candidateDocumentIds = documentIds(candidates);
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

            List<String> proposedQueries = stringList(json.get("nextQueries"), 5);
            List<String> nextQueries = feedbackGuard.retainSafeQueries(originalQuery, proposedQueries, candidates);
            if (!proposedQueries.isEmpty() && nextQueries.size() < proposedQueries.size()) {
                log.warn("[retrieval-judge-v3][candidate feedback blocked traceId={} proposed={} safe={}]",
                        traceId, proposedQueries, nextQueries);
            }
            List<Long> selected = longList(json.get("selectedDocumentIds"), candidateDocumentIds);

            if ((type == DecisionType.REFINE || type == DecisionType.BROADEN) && nextQueries.isEmpty()) {
                // 模型只有依赖候选新增事实才能继续时，宁可停止，也不允许形成自我强化检索闭环。
                if (!proposedQueries.isEmpty()) {
                    return new Decision(DecisionType.ABSTAIN, List.of(), List.of(),
                            "CANDIDATE_FEEDBACK_CONTAMINATION", System.currentTimeMillis() - start, "GUARD");
                }
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
        // Judge 故障不把已有候选的普通查询直接打死；无结果则拒绝。
        if (candidates != null && !candidates.isEmpty()) {
            return new Decision(DecisionType.ACCEPT, List.of(), List.of(), reason, elapsed, "FALLBACK");
        }
        return new Decision(DecisionType.ABSTAIN, List.of(), List.of(), reason, elapsed, "FALLBACK");
    }

    private String buildInput(String query, List<Evidence> candidates, List<Long> documentIds) {
        StringBuilder sb = new StringBuilder(2200);
        sb.append("selectionQuery=").append(query).append('\n');
        sb.append("candidateDocumentIds=").append(documentIds).append('\n');
        sb.append("candidates(observationsOnly=true):\n");
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
