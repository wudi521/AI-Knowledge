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
        PatentQueryPreParser.PatentQueryHints patentHints = preParsePatent(query, policy, history);

        // PATENT 强结构查询：字段/claim 都已由规则确定，不需要先让 LLM 再分类一次。
        if (patentHints != null && patentHints.hasDeterministicExactMetadata()) {
            applyPatentHints(result, patentHints, policy);
            result.setIntent("BIBLIOGRAPHIC_LOOKUP");
            prepareDeterministic(result, "EXACT_METADATA");
            log.info("[analyze][PATENT EXACT_METADATA 规则短路 LLM, applicationNo={}, publicationNo={}, fields={}]",
                    result.getApplicationNo(), result.getPublicationNo(), result.getMetadataFields());
            return result;
        }
        if (patentHints != null && patentHints.hasExactClaim()) {
            applyPatentHints(result, patentHints, policy);
            // intent 已由 applyPatentHints 按 claimQueryType 设置(CLAIM_LOOKUP/CLAIM_DEPENDENCY/CLAIM_SUMMARY)
            prepareDeterministic(result, "EXACT_CLAIM");
            log.info("[analyze][PATENT EXACT_CLAIM 规则短路 LLM, applicationNo={}, publicationNo={}, claimNo={}, intent={}, claimQueryType={}]",
                    result.getApplicationNo(), result.getPublicationNo(), result.getClaimNo(), result.getIntent(), result.getClaimQueryType());
            return result;
        }

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

    private void prepareDeterministic(QueryAnalysis result, String route) {
        result.setRoute(route);
        result.setRewrites(List.of());
        result.setSubQuestions(List.of());
        result.setProducts(List.of());
        result.setSuccess(true);
    }

    private PatentQueryPreParser.PatentQueryHints preParsePatent(String query, DomainQueryPolicy policy,
                                                                 List<ChatTurnDTO> history) {
        if (policy == null || !"PATENT".equalsIgnoreCase(policy.domainCode())) return null;
        // P0-07 多轮: 当前轮无申请号/公布号时, 从历史继承最近一次专利编号, 保持目标文档 Scope
        if (!containsPatentIdentifier(query)) {
            String inherited = extractPatentIdentifierFromHistory(history);
            if (inherited != null) {
                query = query + " " + inherited;
                log.info("[preParsePatent][多轮继承专利编号 {} -> query={}]", inherited, query);
            }
        }
        return patentQueryPreParser.parse(query);
    }

    /** 当前问题是否已含申请号/公布号 */
    private boolean containsPatentIdentifier(String query) {
        if (StrUtil.isBlank(query)) return false;
        return java.util.regex.Pattern.compile("20\\d{10}\\.\\d").matcher(query).find()
                || java.util.regex.Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b").matcher(query).find();
    }

    /** 从历史(最近的 USER 消息优先)提取最近一次申请号/公布号 */
    private String extractPatentIdentifierFromHistory(List<ChatTurnDTO> history) {
        if (history == null || history.isEmpty()) return null;
        java.util.regex.Pattern app = java.util.regex.Pattern.compile("20\\d{10}\\.\\d");
        java.util.regex.Pattern pub = java.util.regex.Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatTurnDTO turn = history.get(i);
            if (turn == null || !"USER".equalsIgnoreCase(turn.getRole()) || StrUtil.isBlank(turn.getContent())) continue;
            String content = turn.getContent();
            java.util.regex.Matcher m = app.matcher(content);
            if (m.find()) return "申请号 " + m.group();
            m = pub.matcher(content);
            if (m.find()) return m.group();
        }
        return null;
    }

    private void applyPatentHints(QueryAnalysis result, PatentQueryPreParser.PatentQueryHints hints, DomainQueryPolicy policy) {
        if (hints == null || policy == null || !"PATENT".equalsIgnoreCase(policy.domainCode())) return;
        result.setApplicationNo(hints.getApplicationNo());
        result.setPublicationNo(hints.getPublicationNo());
        result.setClaimNo(hints.getClaimNo());
        result.setClaimNos(hints.getClaimNos());
        result.setMetadataFields(hints.getMetadataFields());
        result.setClaimQueryType(hints.getClaimQueryType());

        // P0-06: 权利要求问题按子类型(RAW/DEPENDENCY/SUMMARY)区分意图, 不再混为 CLAIM_LOOKUP
        if (hints.getClaimQueryType() != null) {
            switch (hints.getClaimQueryType()) {
                case "DEPENDENCY" -> result.setIntent("CLAIM_DEPENDENCY");
                case "RAW" -> result.setIntent("CLAIM_LOOKUP");
                default -> result.setIntent("CLAIM_SUMMARY");
            }
        } else if (hints.isClaimDependencyIntent()) result.setIntent("CLAIM_DEPENDENCY");
        else if (hints.isClaimIntent()) result.setIntent("CLAIM_LOOKUP");
        else if (hints.isBibliographicIntent() && hints.hasExactDocumentIdentifier()) result.setIntent("BIBLIOGRAPHIC_LOOKUP");
        else if ("OUT_OF_SCOPE".equals(result.getIntent())) {
            log.info("[applyPatentHints][PATENT 模型返回 OUT_OF_SCOPE, 改为 OTHER 进入证据检索]");
            result.setIntent("OTHER");
        }

        if (hints.hasExactClaim()) result.setRoute("EXACT_CLAIM");
        else if (hints.hasDeterministicExactMetadata()) result.setRoute("EXACT_METADATA");
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
        if (policy != null && !policy.useKnowledgeBaseIntents()) return List.of();
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
