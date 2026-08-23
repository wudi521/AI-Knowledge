package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.retrieval.service.domain.DomainQueryPolicy;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import cn.iocoder.yudao.module.retrieval.service.prompt.PromptSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 查询语义理解/改写/拆解。专业领域策略优先于知识库动态意图。 */
@Slf4j
@Service
public class QueryAnalysisService {

    private static final String SYSTEM_PROMPT = """
            你是企业客服知识库的"查询分析器"。给定客户问题(可能附带历史对话), 输出 JSON:
            {"intent":"WARRANTY/REFUND/LOGISTICS/REPAIR/PRICE/OTHER",
             "entities":["关键实体"],"products":["问题明确涉及的产品/品牌名"],
             "province":null,"city":null,"rewrites":["2~3条改写变体"],"sub_questions":[]}
            只输出合法 JSON, 不要其他文字。有历史时仅用于指代消歧；无关历史不得污染当前问题。
            """;
    private static final String INTENT_LIST_MARKER = "__INTENT_LIST__";
    private static final int FALLBACK_QUERY_MAX_LEN = 15;
    private static final String DYNAMIC_SYSTEM_PROMPT = """
            你是企业知识库的查询分析器。给定客户问题(可能附带历史对话), 输出 JSON:
            {"intent":"从以下知识库意图中选择最匹配的一项; 都不匹配输出 OUT_OF_SCOPE",
             "entities":["关键实体"],"products":["产品/品牌; 未提及为空数组"],
             "province":null,"city":null,"rewrites":["2~3条改写变体"],"sub_questions":[]}
            只输出合法 JSON, 不要其他文字。
            意图列表:
            __INTENT_LIST__
            有历史时仅用于当前问题的指代消歧与实体继承；无关历史不得污染当前问题。
            """;

    @Resource private ModelApi modelApi;
    @Resource private PromptSupport promptSupport;
    @Resource private PatentQueryPreParser patentQueryPreParser;

    public QueryAnalysis analyze(String query) { return analyze(query, null); }
    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history) { return analyze(query, history, null); }
    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history, List<IntentDTO> intents) { return analyze(query, history, intents, null); }

    public QueryAnalysis analyze(String query, List<ChatTurnDTO> history, List<IntentDTO> intents, DomainQueryPolicy policy) {
        QueryAnalysis result = new QueryAnalysis();
        result.setSuccess(false);
        PatentQueryPreParser.PatentQueryHints patentHints = preParsePatent(query, policy);
        try {
            List<IntentDTO> effectiveIntents = effectiveIntents(intents, policy);
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(buildSystemPrompt(effectiveIntents, policy));
            req.setUser(buildUserPrompt(query, history));
            String resp = modelApi.chat(req).getCheckedData();
            JSONObject json = parseJson(resp);
            if (json == null) {
                applyPatentHints(result, patentHints, policy);
                return fallbackDisambiguate(result, query, history);
            }
            result.setIntent(clampIntent(json.getStr("intent", "OTHER"), effectiveIntents, policy));
            result.setEntities(strList(json.getJSONArray("entities")));
            result.setProducts(strList(json.getJSONArray("products")));
            result.setProvince(json.getStr("province"));
            result.setCity(json.getStr("city"));
            result.setRewrites(strList(json.getJSONArray("rewrites")));
            result.setSubQuestions(strList(json.getJSONArray("sub_questions")));
            result.setSuccess(true);
            applyPatentHints(result, patentHints, policy);
            log.debug("[analyze][domain={}, intent={}, route={}, applicationNo={}, claimNo={}, rewrites={}]",
                    policy != null ? policy.domainCode() : "GENERAL", result.getIntent(), result.getRoute(),
                    result.getApplicationNo(), result.getClaimNo(), result.getRewrites());
            return result;
        } catch (Exception e) {
            log.warn("[analyze][查询分析失败, 降级用原句检索: {}]", e.getMessage());
            applyPatentHints(result, patentHints, policy);
            return fallbackDisambiguate(result, query, history);
        }
    }

    private PatentQueryPreParser.PatentQueryHints preParsePatent(String query, DomainQueryPolicy policy) {
        if (policy == null || !"PATENT".equalsIgnoreCase(policy.domainCode())) return null;
        return patentQueryPreParser.parse(query);
    }

    /**
     * 确定性规则覆盖专利强结构字段。
     * PATENT 不直接信任 LLM 的 OUT_OF_SCOPE：用户已经显式选择专利知识库时，类似“某装置真的能治疗癌症吗”
     * 仍应进入证据检索，由领域回答策略以“文献记载/声称”安全作答；真正无关问题会因检索无证据自然拒答。
     */
    private void applyPatentHints(QueryAnalysis result, PatentQueryPreParser.PatentQueryHints hints, DomainQueryPolicy policy) {
        if (hints == null || policy == null || !"PATENT".equalsIgnoreCase(policy.domainCode())) return;
        result.setApplicationNo(hints.getApplicationNo());
        result.setPublicationNo(hints.getPublicationNo());
        result.setClaimNo(hints.getClaimNo());
        result.setClaimNos(hints.getClaimNos());

        if (hints.isClaimDependencyIntent()) result.setIntent("CLAIM_DEPENDENCY");
        else if (hints.isClaimIntent()) result.setIntent("CLAIM_LOOKUP");
        else if (hints.isBibliographicIntent() && hints.hasExactDocumentIdentifier()) result.setIntent("BIBLIOGRAPHIC_LOOKUP");
        else if ("OUT_OF_SCOPE".equals(result.getIntent())) {
            log.info("[applyPatentHints][PATENT 模型返回 OUT_OF_SCOPE, 改为 OTHER 进入证据检索]");
            result.setIntent("OTHER");
        }

        if (hints.hasExactClaim()) result.setRoute("EXACT_CLAIM");
        else if (hints.hasExactDocumentIdentifier() && "BIBLIOGRAPHIC_LOOKUP".equals(result.getIntent())) result.setRoute("EXACT_METADATA");
        else if (hints.hasExactDocumentIdentifier()) result.setRoute("SCOPED_RAG");
        else result.setRoute("HYBRID_RAG");

        List<String> entities = result.getEntities() == null ? new ArrayList<>() : new ArrayList<>(result.getEntities());
        addEntity(entities, hints.getApplicationNo());
        addEntity(entities, hints.getPublicationNo());
        if (hints.getClaimNo() != null) addEntity(entities, "权利要求" + hints.getClaimNo());
        result.setEntities(entities);
    }

    private void addEntity(List<String> entities, String value) {
        if (StrUtil.isNotBlank(value) && !entities.contains(value)) entities.add(value);
    }

    private List<IntentDTO> effectiveIntents(List<IntentDTO> intents, DomainQueryPolicy policy) {
        if (policy != null && !policy.useKnowledgeBaseIntents()) {
            if (intents != null && !intents.isEmpty()) log.debug("[effectiveIntents][domain={} 忽略 {} 个知识库动态意图]", policy.domainCode(), intents.size());
            return List.of();
        }
        return intents == null ? List.of() : intents;
    }

    private String buildSystemPrompt(List<IntentDTO> intents, DomainQueryPolicy policy) {
        if (policy != null && StrUtil.isNotBlank(policy.queryAnalysisPrompt())) {
            return promptSupport.get("query-analysis-" + policy.domainCode().toLowerCase(), policy.queryAnalysisPrompt());
        }
        if (intents == null || intents.isEmpty()) return promptSupport.get("query-analysis", SYSTEM_PROMPT);
        String dynamic = promptSupport.get("query-disambiguate", DYNAMIC_SYSTEM_PROMPT);
        int marker = dynamic.indexOf(INTENT_LIST_MARKER);
        if (marker < 0) return promptSupport.get("query-analysis", SYSTEM_PROMPT);
        StringBuilder sb = new StringBuilder(dynamic.length() + 256).append(dynamic, 0, marker);
        for (IntentDTO intent : intents) {
            if (intent == null || StrUtil.isBlank(intent.getName())) continue;
            sb.append("- ").append(intent.getName());
            if (StrUtil.isNotBlank(intent.getDescription())) sb.append(": ").append(StrUtil.trim(intent.getDescription()));
            sb.append('\n');
        }
        sb.append(dynamic, marker + INTENT_LIST_MARKER.length(), dynamic.length());
        return sb.toString();
    }

    private String clampIntent(String rawIntent, List<IntentDTO> intents, DomainQueryPolicy policy) {
        String trimmed = StrUtil.blankToDefault(StrUtil.trim(rawIntent), "OTHER");
        if (policy != null && policy.supportedIntents() != null && !policy.supportedIntents().isEmpty()) {
            for (String supported : policy.supportedIntents()) if (supported.equalsIgnoreCase(trimmed)) return supported;
            log.warn("[clampIntent][domain={} 模型返回非法领域意图 {}, 钳制 OTHER]", policy.domainCode(), trimmed);
            return policy.supportedIntents().contains("OTHER") ? "OTHER" : "OUT_OF_SCOPE";
        }
        if (intents == null || intents.isEmpty()) return trimmed;
        for (IntentDTO intent : intents) if (intent != null && trimmed.equals(intent.getName())) return intent.getName();
        return "OUT_OF_SCOPE";
    }

    private String buildUserPrompt(String query, List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) return "当前问题: " + query;
        StringBuilder sb = new StringBuilder("历史对话(时间从早到晚):\n");
        for (ChatTurnDTO turn : history) {
            if (turn == null || StrUtil.isBlank(turn.getContent())) continue;
            sb.append('[').append("USER".equalsIgnoreCase(turn.getRole()) ? "用户" : "AI").append("] ")
                    .append(StrUtil.trim(turn.getContent())).append('\n');
        }
        return sb.append("当前问题: ").append(query).toString();
    }

    private QueryAnalysis fallbackDisambiguate(QueryAnalysis result, String query, List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty() || StrUtil.isBlank(query) || query.trim().length() > FALLBACK_QUERY_MAX_LEN) return result;
        String lastUser = lastUserContent(history);
        if (StrUtil.isBlank(lastUser)) return result;
        result.setRewrites(List.of(mergeLastUserAndQuery(StrUtil.trim(lastUser), query.trim())));
        return result;
    }

    private String lastUserContent(List<ChatTurnDTO> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatTurnDTO turn = history.get(i);
            if (turn != null && "USER".equalsIgnoreCase(turn.getRole()) && StrUtil.isNotBlank(turn.getContent())) return turn.getContent();
        }
        return null;
    }

    private String mergeLastUserAndQuery(String lastUser, String query) {
        if (lastUser.contains(query)) return lastUser;
        if (query.contains(lastUser)) return query;
        return lastUser + " " + query;
    }

    private JSONObject parseJson(String resp) {
        if (StrUtil.isBlank(resp)) return null;
        int start = resp.indexOf('{'), end = resp.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try { return JSONUtil.parseObj(resp.substring(start, end + 1)); } catch (Exception e) { return null; }
    }

    private List<String> strList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (Object o : arr) if (o != null && StrUtil.isNotBlank(o.toString())) list.add(o.toString());
        return list;
    }
}
