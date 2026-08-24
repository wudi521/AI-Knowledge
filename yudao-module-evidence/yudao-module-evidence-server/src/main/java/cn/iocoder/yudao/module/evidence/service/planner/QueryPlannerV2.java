package cn.iocoder.yudao.module.evidence.service.planner;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Query Planner V2 的语义兜底层。
 *
 * <p>确定性强语义（精确原文、跨实体比较、逐实体语义）由 {@link QueryPlannerFacade}
 * 在进入本类之前处理。本类只负责：
 * <ul>
 *   <li>明显 Structured/主观歧义的确定性收口；</li>
 *   <li>剩余模糊问题的一次 LLM 类型化规划；</li>
 *   <li>LLM 输出白名单校验和安全回退。</li>
 * </ul>
 *
 * <p>这里刻意只使用仓库当前版本稳定存在的基础 API，避免 Planner 因 Hutool 重载差异
 * 或过度复杂的辅助调用导致主工程无法编译。</p>
 */
@Slf4j
@Component
public class QueryPlannerV2 {

    private static final String DEFAULT_PROMPT = """
            你是企业知识平台的 Query Planner。你的任务不是回答问题，而是选择安全的执行计划。
            只输出 JSON，不得输出 SQL、代码或解释。
            queryClass 只能是 STRUCTURED_QUERY/SEMANTIC_QUERY/COMPOSITE_QUERY/EVIDENCE_QUERY/CLARIFY/ABSTAIN。
            executionMode 只能是 STRUCTURED/SCOPED_RAG/PER_ENTITY_SEMANTIC/CROSS_ENTITY_COMPARE/HYBRID_RAG/EXACT_TEXT_SEARCH/COMPOSITE。
            comparisonType 只能是 SIMILARITY/COMMONALITY/DIFFERENCE/PAIR_COMPARE/NEAREST_TO_ANCHOR/PAIRWISE_RANK/NONE。
            completenessPolicy 只能是 COMPLETE_REQUIRED/BEST_EFFORT/TOP_K_ALLOWED。
            原则：
            1. 统计、完整列举、排序、分组优先 STRUCTURED，禁止用 TopK RAG 推断全集；
            2. 单对象语义问题用 SCOPED_RAG；多个明确对象分别回答用 PER_ENTITY_SEMANTIC；
            3. 相似/共同点/区别/比较用 CROSS_ENTITY_COMPARE；普通发现型语义问题才用 HYBRID_RAG；
            4. 用户明确说原文出现/包含某词时用 EXACT_TEXT_SEARCH；
            5. 主观哪个好/更先进/更有价值且没有评价标准时 CLARIFY；
            6. 不得创造未注册 field/metric。
            JSON 格式：
            {"queryClass":"SEMANTIC_QUERY","executionMode":"HYBRID_RAG","entityType":null,
             "projections":[],"metrics":[],"operation":"NONE",
             "comparisonType":"NONE","completenessPolicy":"BEST_EFFORT",
             "perEntityTopK":2,"requireDistinctEntities":false,"coveragePolicy":"BEST_EFFORT",
             "requiresClarification":false,"clarificationQuestion":null}
            """;

    private final CompletenessGuard completenessGuard;
    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;
    private final ModelApi modelApi;
    private final PromptSupport promptSupport;
    private final QueryPlanValidator validator;

    public QueryPlannerV2(CompletenessGuard completenessGuard,
                          DomainFieldRegistry fieldRegistry,
                          DomainMetricRegistry metricRegistry,
                          ModelApi modelApi,
                          PromptSupport promptSupport,
                          QueryPlanValidator validator) {
        this.completenessGuard = completenessGuard;
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
        this.modelApi = modelApi;
        this.promptSupport = promptSupport;
        this.validator = validator;
    }

    public QueryPlan plan(String query, String domainCode, List<ChatTurnDTO> history,
                          String contextResolutionJson) {
        List<Long> contextIds = parseContextEntityIds(contextResolutionJson);

        QueryPlan deterministic = deterministic(query, domainCode, contextIds);
        if (deterministic != null) {
            return deterministic;
        }

        QueryPlan llmPlan = semanticPlan(query, domainCode, history, contextIds);
        QueryPlanValidator.Validation validation = validator.validate(llmPlan);
        if (validation.valid()) {
            return llmPlan;
        }

        log.warn("[plan][invalid LLM QueryPlan, reason={}, query={}]",
                validation.reasonCode(), abbreviate(query, 80));
        return fallback(domainCode, contextIds, validation.reasonCode());
    }

    private QueryPlan deterministic(String query, String domainCode, List<Long> contextIds) {
        if (isBlank(query)) {
            return clarify(domainCode, "请描述你想查询的问题。", "EMPTY_QUERY");
        }

        // 主观评价没有显式标准时必须先反问，禁止模型自行发明评价标准。
        if (isSubjectiveComparison(query) && !hasExplicitCriterion(query)) {
            return clarify(domainCode,
                    "请说明你希望按什么标准比较，例如技术相似度、权利要求数量、申请时间或其他明确指标。",
                    "MISSING_COMPARISON_CRITERION");
        }

        // 完整统计/列举必须进入 Structured，不能交给 TopK RAG。
        if (completenessGuard.isStructuredCandidate(query)
                || completenessGuard.requiresCompleteDataset(query)) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.STRUCTURED_QUERY)
                    .executionMode(ExecutionMode.STRUCTURED)
                    .domainCode(domainCode)
                    .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .completenessPolicy(CompletenessPolicy.COMPLETE_REQUIRED)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }
        return null;
    }

    private QueryPlan semanticPlan(String query, String domainCode, List<ChatTurnDTO> history,
                                   List<Long> contextIds) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("query-planner-v2", DEFAULT_PROMPT));
            req.setUser(buildPlannerInput(query, domainCode, history, contextIds));
            req.setTemperature(0D);
            req.setScenario("query-planner-v2");

            CommonResult<String> response = modelApi.chat(req);
            String raw = response == null ? null : response.getCheckedData();
            JSONObject json = parseJson(raw);
            if (json == null) {
                return fallback(domainCode, contextIds, "PLANNER_PARSE_FAILED");
            }

            boolean requiresClarification = booleanValue(json.get("requiresClarification"), false);
            QueryPlan plan = QueryPlan.builder()
                    .queryClass(enumValue(QueryClass.class, stringValue(json.get("queryClass")),
                            QueryClass.SEMANTIC_QUERY))
                    .executionMode(enumValue(ExecutionMode.class, stringValue(json.get("executionMode")),
                            ExecutionMode.HYBRID_RAG))
                    .domainCode(domainCode)
                    .entityType(stringValue(json.get("entityType")))
                    .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .projections(stringList(json.get("projections")))
                    .metrics(stringList(json.get("metrics")))
                    .operation(enumValue(Operation.class, stringValue(json.get("operation")), Operation.NONE))
                    .comparisonType(enumValue(ComparisonType.class, stringValue(json.get("comparisonType")),
                            ComparisonType.NONE))
                    .completenessPolicy(enumValue(CompletenessPolicy.class,
                            stringValue(json.get("completenessPolicy")), CompletenessPolicy.BEST_EFFORT))
                    .perEntityTopK(clamp(integerValue(json.get("perEntityTopK"), 2), 1, 8))
                    .requireDistinctEntities(booleanValue(json.get("requireDistinctEntities"), false))
                    .coveragePolicy(defaultIfBlank(stringValue(json.get("coveragePolicy")), "BEST_EFFORT"))
                    .requiresClarification(requiresClarification)
                    .clarificationQuestion(stringValue(json.get("clarificationQuestion")))
                    .plannerSource("LLM")
                    .build();

            if (requiresClarification) {
                plan.setQueryClass(QueryClass.CLARIFY);
            }
            return plan;
        } catch (Exception e) {
            log.warn("[plan][Semantic Planner failed, fallback: {}]", e.getMessage());
            return fallback(domainCode, contextIds, "PLANNER_UNAVAILABLE");
        }
    }

    private String buildPlannerInput(String query, String domainCode, List<ChatTurnDTO> history,
                                     List<Long> contextIds) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("domain=").append(domainCode).append('\n');
        sb.append("allowedFields=").append(registeredFields(domainCode)).append('\n');
        sb.append("allowedMetrics=").append(registeredMetrics(domainCode)).append('\n');
        sb.append("previousResultEntityCount=").append(contextIds.size()).append('\n');

        if (history != null && !history.isEmpty()) {
            sb.append("recentContext:\n");
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                ChatTurnDTO turn = history.get(i);
                if (turn == null || isBlank(turn.getContent())) {
                    continue;
                }
                sb.append(defaultIfBlank(turn.getRole(), "UNKNOWN"))
                        .append(':')
                        .append(abbreviate(turn.getContent(), 180))
                        .append('\n');
            }
        }
        sb.append("currentQuery=").append(query == null ? "" : query);
        return sb.toString();
    }

    private String registeredFields(String domainCode) {
        if (isBlank(domainCode)) {
            return "[]";
        }
        List<String> codes = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.all(domainCode)) {
            if (field != null && !isBlank(field.getFieldCode()) && !codes.contains(field.getFieldCode())) {
                codes.add(field.getFieldCode());
            }
        }
        return "[" + String.join(",", codes) + "]";
    }

    private String registeredMetrics(String domainCode) {
        if (isBlank(domainCode)) {
            return "[]";
        }
        List<String> codes = new ArrayList<>();
        for (MetricDefinition metric : metricRegistry.all(domainCode)) {
            if (metric != null && !isBlank(metric.getMetricCode()) && !codes.contains(metric.getMetricCode())) {
                codes.add(metric.getMetricCode());
            }
        }
        return "[" + String.join(",", codes) + "]";
    }

    private QueryPlan fallback(String domainCode, List<Long> contextIds, String reasonCode) {
        return QueryPlan.builder()
                .queryClass(QueryClass.SEMANTIC_QUERY)
                .executionMode(contextIds.isEmpty()
                        ? ExecutionMode.HYBRID_RAG : ExecutionMode.PER_ENTITY_SEMANTIC)
                .domainCode(domainCode)
                .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                .entityIds(contextIds)
                .comparisonType(ComparisonType.NONE)
                .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                .reasonCode(reasonCode)
                .plannerSource("FALLBACK")
                .build();
    }

    private QueryPlan clarify(String domainCode, String question, String reasonCode) {
        return QueryPlan.builder()
                .queryClass(QueryClass.CLARIFY)
                .domainCode(domainCode)
                .requiresClarification(true)
                .clarificationQuestion(question)
                .reasonCode(reasonCode)
                .plannerSource("DETERMINISTIC")
                .build();
    }

    private boolean isSubjectiveComparison(String query) {
        return containsAny(query, "哪个好", "哪个更好", "更先进", "最先进", "更有价值", "最有价值",
                "更优秀", "最好", "最优", "更强", "最强");
    }

    private boolean hasExplicitCriterion(String query) {
        return containsAny(query, "按", "根据", "以", "从", "相似度", "权利要求", "申请时间", "申请日",
                "公布时间", "公开日", "数量", "成本", "价格", "性能", "准确率", "效率");
    }

    private List<Long> parseContextEntityIds(String jsonText) {
        if (isBlank(jsonText)) {
            return List.of();
        }
        try {
            JSONObject object = JSONUtil.parseObj(jsonText);
            Object rawIds = object.get("explicitEntityIds");
            if (!(rawIds instanceof JSONArray array)) {
                return List.of();
            }
            List<Long> result = new ArrayList<>();
            for (Object value : array) {
                Long id = longValue(value);
                if (id != null && !result.contains(id)) {
                    result.add(id);
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JSONObject parseJson(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            return JSONUtil.parseObj(raw.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof JSONArray array)) {
            return result;
        }
        for (Object item : array) {
            String text = stringValue(item);
            if (!isBlank(text) && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (isBlank(raw)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private String defaultIfBlank(String text, String fallback) {
        return isBlank(text) ? fallback : text;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength));
    }
}
