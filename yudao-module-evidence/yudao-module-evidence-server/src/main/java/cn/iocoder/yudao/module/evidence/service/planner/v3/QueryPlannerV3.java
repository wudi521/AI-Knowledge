package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Engine V3 唯一自然语言语义编译器。
 *
 * <p>所有用户自然语言先由这里编译成 Selection + Actions。下游 Structured/Retrieval Executor
 * 只执行 typed intent，不再依据关键词重新判断用户意图。</p>
 */
@Slf4j
@Component
public class QueryPlannerV3 {

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");
    private static final Pattern QUOTED = Pattern.compile("[\\\"“‘']([^\\\"”’']{1,200})[\\\"”’']");

    private static final String DEFAULT_PROMPT = """
            你是企业知识平台 Query Compiler。你只负责把用户自然语言编译成业务语义 IR，不回答问题。
            只输出一个 JSON 对象，不要 Markdown，不要 SQL，不要 ES DSL，不要解释推理过程。

            核心模型：先判断 Selection（用户要找哪些对象），再判断 Actions（找到后做什么）。
            不要判断“简单/复杂问题”，复杂度由 Selection + Actions 自动产生。

            selection.type 只能是：
            CURRENT_SCOPE：当前知识库全部实体；
            RESULT_SET：上一轮明确实体集合；
            EXACT_ENTITY：通过已注册结构化字段精确定位对象；
            STRUCTURED_FILTER：通过已注册字段和白名单操作符过滤对象；
            SEMANTIC：条件属于“相关/涉及/采用/解决/类似/关于”等语义概念，不能可靠映射为结构化字段；
            EXACT_TEXT：用户明确要求原文逐字出现/包含某词或短语。

            action.type 只能是：PROJECT_FIELDS/LIST/COUNT/AGGREGATE/COMPARE/SUMMARIZE/ANSWER。
            PROJECT_FIELDS 必须填写 fields；AGGREGATE 填 metric/operation；COMPARE 可填 compareType；
            SUMMARIZE/ANSWER/COMPARE 可填写 action.query 作为后续证据检索焦点。

            重要规则：
            1. “发明人/申请号/公布号”等字段只是 Action 或结构化条件，不能仅因为出现这些词就把整句当成结构化查询。
            2. “视频技术相关专利的发明人是谁”应是 selection=SEMANTIC(query=视频技术)，action=PROJECT_FIELDS(INVENTOR)。
            3. “标题包含磁涌的专利发明人”应是 selection=STRUCTURED_FILTER(field=TITLE,operator=CONTAINS,value=磁涌)，action=PROJECT_FIELDS(INVENTOR)。
            4. “当前知识库有几个专利”应是 CURRENT_SCOPE + COUNT。
            5. “CN xxx 的发明人”应是 EXACT_ENTITY(PUBLICATION_NO=xxx) + PROJECT_FIELDS(INVENTOR)。
            6. “原文包含磁涌的专利有哪些”应是 EXACT_TEXT(query=磁涌) + LIST。
            7. 不能创造 allowedFields/allowedMetrics 之外的字段或指标；语义概念不是字段时必须用 SEMANTIC。
            8. queryVariants 只给 SEMANTIC 第一轮召回使用，最多 5 个，必须保持原意，不能编造领域事实。
            9. 如果用户意图确实缺必要条件，requiresClarification=true 并给 clarificationQuestion。

            输出格式：
            {
              "version":"3",
              "entityType":"PATENT_DOCUMENT",
              "selection":{"type":"SEMANTIC","query":"视频技术","field":null,"operator":null,"values":[],"queryVariants":["视频技术","视频存储技术"]},
              "actions":[{"type":"PROJECT_FIELDS","fields":["INVENTOR"],"metric":null,"operation":null,"compareType":null,"query":null,"limit":null}],
              "completeness":"BEST_EFFORT",
              "requiresClarification":false,
              "clarificationQuestion":null,
              "reasonCode":null
            }
            """;

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final QueryIntentValidatorV3 validator;

    public QueryPlannerV3(DomainFieldRegistry fieldRegistry,
                          DomainMetricRegistry metricRegistry,
                          ModelApi modelApi,
                          PromptSupport promptSupport,
                          QueryIntentValidatorV3 validator) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.validator = validator;
    }

    public QueryIntentV3 plan(String query, String domainCode, List<ChatTurnDTO> history,
                              List<Long> explicitEntityIds, String traceId) {
        if (StrUtil.isBlank(query)) {
            return clarify(domainCode, "请描述你想查询的问题。", "EMPTY_QUERY", 0L);
        }
        long start = System.currentTimeMillis();
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("query-planner-v3", DEFAULT_PROMPT));
            req.setUser(buildInput(query, domainCode, history, explicitEntityIds));
            req.setTemperature(0D);
            req.setScenario("query-planner-v3");
            req.setTraceId(traceId);
            CommonResult<String> response = modelApi.chat(req);
            String raw = response == null ? null : response.getCheckedData();
            JSONObject json = parseJson(raw);
            long elapsed = System.currentTimeMillis() - start;
            if (json == null) return unavailable(domainCode, "PLANNER_PARSE_FAILED", elapsed);

            QueryIntentV3 intent = parseIntent(json, domainCode, elapsed);
            QueryIntentValidatorV3.Validation validation = validator.validate(intent);
            if (!validation.valid()) {
                log.warn("[plan-v3][invalid intent reason={} query={}]", validation.reasonCode(), StrUtil.maxLength(query, 100));
                return unavailable(domainCode, validation.reasonCode(), elapsed);
            }
            return intent;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[plan-v3][planner unavailable query={} error={}]", StrUtil.maxLength(query, 100), e.getMessage());
            return unavailable(domainCode, "PLANNER_UNAVAILABLE", elapsed);
        }
    }

    private QueryIntentV3 parseIntent(JSONObject json, String domainCode, long elapsed) {
        QueryIntentV3.Selection selection = null;
        JSONObject s = json.getJSONObject("selection");
        if (s != null) {
            selection = QueryIntentV3.Selection.builder()
                    .type(enumValue(QueryIntentV3.SelectionType.class, s.getStr("type")))
                    .query(s.getStr("query"))
                    .field(upper(s.getStr("field")))
                    .operator(upper(s.getStr("operator")))
                    .values(stringList(s.get("values"), 20))
                    .queryVariants(stringList(s.get("queryVariants"), 5))
                    .build();
        }

        List<QueryIntentV3.Action> actions = new ArrayList<>();
        JSONArray array = json.getJSONArray("actions");
        if (array != null) {
            for (Object item : array) {
                if (!(item instanceof JSONObject a)) continue;
                QueryIntentV3.ActionType type = enumValue(QueryIntentV3.ActionType.class, a.getStr("type"));
                if (type == null) continue;
                actions.add(QueryIntentV3.Action.builder()
                        .type(type)
                        .fields(stringListUpper(a.get("fields"), 12))
                        .metric(upper(a.getStr("metric")))
                        .operation(upper(a.getStr("operation")))
                        .compareType(upper(a.getStr("compareType")))
                        .query(a.getStr("query"))
                        .limit(integerValue(a.get("limit")))
                        .build());
            }
        }

        return QueryIntentV3.builder()
                .version("3")
                .domainCode(domainCode)
                .entityType(StrUtil.blankToDefault(json.getStr("entityType"), defaultEntityType(domainCode)))
                .selection(selection)
                .actions(actions)
                .completeness(StrUtil.blankToDefault(json.getStr("completeness"), "BEST_EFFORT"))
                .requiresClarification(Boolean.TRUE.equals(json.getBool("requiresClarification")))
                .clarificationQuestion(json.getStr("clarificationQuestion"))
                .reasonCode(json.getStr("reasonCode"))
                .plannerSource("LLM")
                .plannerElapsedMs(elapsed)
                .build();
    }

    private String buildInput(String query, String domainCode, List<ChatTurnDTO> history,
                              List<Long> explicitEntityIds) {
        StringBuilder sb = new StringBuilder(1200);
        sb.append("domain=").append(StrUtil.blankToDefault(domainCode, "GENERAL")).append('\n');
        sb.append("allowedFields=").append(fieldSchema(domainCode)).append('\n');
        sb.append("allowedMetrics=").append(metricSchema(domainCode)).append('\n');
        sb.append("lexicalFacts=").append(lexicalFacts(query)).append('\n');
        sb.append("previousResultEntityIds=").append(explicitEntityIds == null ? List.of() : explicitEntityIds).append('\n');
        if (history != null && !history.isEmpty()) {
            sb.append("recentContext:\n");
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                ChatTurnDTO turn = history.get(i);
                if (turn == null || StrUtil.isBlank(turn.getContent())) continue;
                sb.append(StrUtil.blankToDefault(turn.getRole(), "UNKNOWN")).append(':')
                        .append(StrUtil.maxLength(turn.getContent(), 220)).append('\n');
            }
        }
        sb.append("currentQuery=").append(query);
        return sb.toString();
    }

    private String fieldSchema(String domainCode) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(domainCode)) return "[]";
        for (FieldDefinition f : fieldRegistry.all(domainCode)) {
            if (f == null || StrUtil.isBlank(f.getFieldCode())) continue;
            out.add(f.getFieldCode() + "(aliases=" + (f.getAliases() == null ? List.of() : f.getAliases())
                    + ",filterable=" + f.isFilterable() + ")");
        }
        return out.toString();
    }

    private String metricSchema(String domainCode) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(domainCode)) return "[]";
        for (MetricDefinition m : metricRegistry.all(domainCode)) {
            if (m == null || StrUtil.isBlank(m.getMetricCode())) continue;
            out.add(m.getMetricCode() + "(operations=" + m.getSupportedOperations() + ")");
        }
        return out.toString();
    }

    private String lexicalFacts(String query) {
        List<String> facts = new ArrayList<>();
        Matcher app = APPLICATION_NO.matcher(query);
        while (app.find()) facts.add("APPLICATION_NO=" + app.group());
        Matcher pub = PUBLICATION_NO.matcher(query);
        while (pub.find()) facts.add("PUBLICATION_NO=" + pub.group());
        Matcher quoted = QUOTED.matcher(query);
        while (quoted.find()) facts.add("QUOTED_LITERAL=" + quoted.group(1));
        return facts.toString();
    }

    private QueryIntentV3 unavailable(String domainCode, String reason, long elapsed) {
        return QueryIntentV3.builder()
                .version("3").domainCode(domainCode).entityType(defaultEntityType(domainCode))
                .selection(QueryIntentV3.Selection.builder().type(QueryIntentV3.SelectionType.CURRENT_SCOPE).build())
                .actions(List.of(QueryIntentV3.Action.builder().type(QueryIntentV3.ActionType.ANSWER).build()))
                .requiresClarification(true)
                .clarificationQuestion("当前查询规划服务未能生成可靠执行计划，请稍后重试。")
                .reasonCode(reason).plannerSource("FALLBACK").plannerElapsedMs(elapsed).build();
    }

    private QueryIntentV3 clarify(String domainCode, String question, String reason, long elapsed) {
        QueryIntentV3 intent = unavailable(domainCode, reason, elapsed);
        intent.setClarificationQuestion(question);
        return intent;
    }

    private JSONObject parseJson(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            return JSONUtil.parseObj(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(Object raw, int limit) {
        if (!(raw instanceof JSONArray array)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : array) {
            String value = item == null ? null : String.valueOf(item).trim();
            if (StrUtil.isNotBlank(value) && !out.contains(value)) out.add(value);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private List<String> stringListUpper(Object raw, int limit) {
        return stringList(raw, limit).stream().map(this::upper).toList();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return null; }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (StrUtil.isBlank(value)) return null;
        try { return Enum.valueOf(type, value.trim().toUpperCase()); } catch (Exception e) { return null; }
    }

    private String upper(String value) {
        return StrUtil.isBlank(value) ? null : value.trim().toUpperCase();
    }

    private String defaultEntityType(String domainCode) {
        return "PATENT".equalsIgnoreCase(domainCode) ? "PATENT_DOCUMENT" : "KNOWLEDGE_DOCUMENT";
    }
}
