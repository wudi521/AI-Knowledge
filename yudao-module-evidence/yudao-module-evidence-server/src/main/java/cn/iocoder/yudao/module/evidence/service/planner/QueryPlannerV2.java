package cn.iocoder.yudao.module.evidence.service.planner;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Query Planner V2：检索前先生成类型安全的执行计划。
 * <p>
 * 优先级：明确比较/原文/逐实体/主观歧义 → Structured Complete → 语义 Planner LLM。
 * 这样“哪些专利比较相似”不会因为“哪些”被误抢成普通 LIST/Structured。
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
            4. 用户明确说“原文出现/包含某词/精确搜索”用 EXACT_TEXT_SEARCH；
            5. 主观“哪个好/更先进/更有价值”且没有评价标准时 CLARIFY；
            6. 不得创造未注册 field/metric。
            JSON 格式：
            {"queryClass":"...","executionMode":"...","entityType":null,
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

    public QueryPlan plan(String query, String domainCode, List<ChatTurnDTO> history, String contextResolutionJson) {
        List<Long> contextIds = parseContextEntityIds(contextResolutionJson);
        QueryPlan deterministic = deterministic(query, domainCode, contextIds);
        if (deterministic != null) return deterministic;

        QueryPlan llm = semanticPlan(query, domainCode, history, contextIds);
        QueryPlanValidator.Validation validation = validator.validate(llm);
        if (validation.valid()) return llm;

        log.warn("[plan][LLM QueryPlan 非法, reason={}, query={}]", validation.reasonCode(), StrUtil.maxLength(query, 80));
        return fallback(query, domainCode, contextIds, validation.reasonCode());
    }

    private QueryPlan deterministic(String query, String domainCode, List<Long> contextIds) {
        if (StrUtil.isBlank(query)) return clarify(domainCode, "请描述你想查询的问题。", "EMPTY_QUERY");

        // 1. 主观比较没有评价标准时必须先反问，禁止模型自行发明“好/先进/价值”的标准。
        if (isSubjectiveComparison(query) && !hasExplicitCriterion(query)) {
            return clarify(domainCode,
                    "请说明你希望按什么标准比较，例如技术相似度、权利要求数量、申请时间或其他明确指标。",
                    "MISSING_COMPARISON_CRITERION");
        }

        // 2. 强比较语义优先于“哪些/分别”等泛结构化词。
        ComparisonType comparison = detectComparison(query);
        if (comparison != ComparisonType.NONE) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.CROSS_ENTITY_COMPARE)
                    .domainCode(domainCode)
                    .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .comparisonType(comparison)
                    .perEntityTopK(2)
                    .requireDistinctEntities(true)
                    .coveragePolicy("ALL")
                    .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }

        // 3. 精确原文搜索不是普通语义召回，也不能被“哪些”抢成 Structured LIST。
        if (isExactText(query)) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.EXACT_TEXT_SEARCH)
                    .domainCode(domainCode)
                    .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .completenessPolicy(CompletenessPolicy.COMPLETE_REQUIRED)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }

        // 4. 上一轮已有明确实体集，且当前问“分别的核心技术”等语义属性 → 逐实体 hard-scope。
        if (!contextIds.isEmpty() && isPerEntitySemantic(query)) {
            return QueryPlan.builder()
                    .queryClass(QueryClass.SEMANTIC_QUERY)
                    .executionMode(ExecutionMode.PER_ENTITY_SEMANTIC)
                    .domainCode(domainCode)
                    .scopeType("PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .perEntityTopK(4)
                    .coveragePolicy("ALL")
                    .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                    .plannerSource("DETERMINISTIC")
                    .build();
        }

        // 5. 最后才进入完整数据集 Structured。TopK 永远不能证明全集。
        if (completenessGuard.isStructuredCandidate(query) || completenessGuard.requiresCompleteDataset(query)) {
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

    private QueryPlan semanticPlan(String query, String domainCode, List<ChatTurnDTO> history, List<Long> contextIds) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("query-planner-v2", DEFAULT_PROMPT));
            req.setUser(buildPlannerInput(query, domainCode, history, contextIds));
            String raw = modelApi.chat(req).getCheckedData();
            JSONObject json = parseJson(raw);
            if (json == null) return fallback(query, domainCode, contextIds, "PLANNER_PARSE_FAILED");

            QueryPlan plan = QueryPlan.builder()
                    .queryClass(enumValue(QueryClass.class, json.getStr("queryClass"), QueryClass.SEMANTIC_QUERY))
                    .executionMode(enumValue(ExecutionMode.class, json.getStr("executionMode"), ExecutionMode.HYBRID_RAG))
                    .domainCode(domainCode)
                    .entityType(json.getStr("entityType"))
                    .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                    .entityIds(contextIds)
                    .projections(strList(json.getJSONArray("projections")))
                    .metrics(strList(json.getJSONArray("metrics")))
                    .operation(enumValue(Operation.class, json.getStr("operation"), Operation.NONE))
                    .comparisonType(enumValue(ComparisonType.class, json.getStr("comparisonType"), ComparisonType.NONE))
                    .completenessPolicy(enumValue(CompletenessPolicy.class, json.getStr("completenessPolicy"), CompletenessPolicy.BEST_EFFORT))
                    .perEntityTopK(clamp(json.getInt("perEntityTopK"), 1, 8, 2))
                    .requireDistinctEntities(json.getBool("requireDistinctEntities", false))
                    .coveragePolicy(StrUtil.blankToDefault(json.getStr("coveragePolicy"), "BEST_EFFORT"))
                    .requiresClarification(json.getBool("requiresClarification", false))
                    .clarificationQuestion(json.getStr("clarificationQuestion"))
                    .plannerSource("LLM")
                    .build();
            if (plan.isRequiresClarification()) plan.setQueryClass(QueryClass.CLARIFY);
            return plan;
        } catch (Exception e) {
            log.warn("[plan][Semantic Planner 失败, 回退 HYBRID: {}]", e.getMessage());
            return fallback(query, domainCode, contextIds, "PLANNER_UNAVAILABLE");
        }
    }

    private String buildPlannerInput(String query, String domainCode, List<ChatTurnDTO> history, List<Long> contextIds) {
        String fields = domainCode == null ? "[]" : fieldRegistry.all(domainCode).stream()
                .map(f -> f.getFieldCode()).distinct().collect(Collectors.joining(",", "[", "]"));
        String metrics = domainCode == null ? "[]" : metricRegistry.all(domainCode).stream()
                .map(m -> m.getMetricCode()).distinct().collect(Collectors.joining(",", "[", "]"));
        StringBuilder sb = new StringBuilder();
        sb.append("domain=").append(domainCode).append('\n');
        sb.append("allowedFields=").append(fields).append('\n');
        sb.append("allowedMetrics=").append(metrics).append('\n');
        sb.append("previousResultEntityCount=").append(contextIds.size()).append('\n');
        if (history != null && !history.isEmpty()) {
            sb.append("recentContext:\n");
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                ChatTurnDTO t = history.get(i);
                if (t != null && StrUtil.isNotBlank(t.getContent())) {
                    sb.append(t.getRole()).append(':').append(StrUtil.maxLength(t.getContent(), 180)).append('\n');
                }
            }
        }
        return sb.append("currentQuery=").append(query).toString();
    }

    private QueryPlan fallback(String query, String domainCode, List<Long> contextIds, String reason) {
        return QueryPlan.builder()
                .queryClass(QueryClass.SEMANTIC_QUERY)
                .executionMode(contextIds.isEmpty() ? ExecutionMode.HYBRID_RAG : ExecutionMode.PER_ENTITY_SEMANTIC)
                .domainCode(domainCode)
                .scopeType(contextIds.isEmpty() ? "CURRENT_KB" : "PREVIOUS_RESULT_SET")
                .entityIds(contextIds)
                .comparisonType(ComparisonType.NONE)
                .completenessPolicy(CompletenessPolicy.BEST_EFFORT)
                .reasonCode(reason)
                .plannerSource("FALLBACK")
                .build();
    }

    private QueryPlan clarify(String domainCode, String question, String reason) {
        return QueryPlan.builder().queryClass(QueryClass.CLARIFY).domainCode(domainCode)
                .requiresClarification(true).clarificationQuestion(question).reasonCode(reason)
                .plannerSource("DETERMINISTIC").build();
    }

    private ComparisonType detectComparison(String query) {
        if (StrUtil.containsAny(query, "最相似", "最像", "最接近", "类似")) return ComparisonType.SIMILARITY;
        if (StrUtil.containsAny(query, "共同点", "共性", "相同点")) return ComparisonType.COMMONALITY;
        if (StrUtil.containsAny(query, "区别", "差异", "不同点")) return ComparisonType.DIFFERENCE;
        if (StrUtil.containsAny(query, "相似", "比较", "对比")) return ComparisonType.PAIR_COMPARE;
        return ComparisonType.NONE;
    }

    private boolean isSubjectiveComparison(String query) {
        return StrUtil.containsAny(query, "哪个好", "哪个更好", "更先进", "最先进", "更有价值", "最有价值",
                "更优秀", "最好", "最优", "更强", "最强");
    }

    private boolean hasExplicitCriterion(String query) {
        return StrUtil.containsAny(query, "按", "根据", "以", "从", "相似度", "权利要求", "申请时间", "申请日",
                "公布时间", "公开日", "数量", "成本", "价格", "性能", "准确率", "效率");
    }

    private boolean isPerEntitySemantic(String query) {
        boolean semantic = StrUtil.containsAny(query, "核心技术", "技术方案", "技术原理", "解决什么", "背景技术",
                "实施例", "实施方式", "主要内容", "总结", "概括");
        boolean perEntity = StrUtil.containsAny(query, "分别", "各自", "逐个", "每个", "它们", "这些", "这几个");
        return semantic && perEntity;
    }

    private boolean isExactText(String query) {
        return StrUtil.containsAny(query, "原文出现", "原文包含", "精确搜索", "精确匹配", "出现过", "出现了哪些");
    }

    private List<Long> parseContextEntityIds(String json) {
        if (StrUtil.isBlank(json)) return List.of();
        try {
            JSONArray arr = JSONUtil.parseObj(json).getJSONArray("explicitEntityIds");
            if (arr == null) return List.of();
            List<Long> ids = new ArrayList<>();
            for (Object value : arr) {
                if (value instanceof Number n) ids.add(n.longValue());
                else if (value != null) ids.add(Long.parseLong(String.valueOf(value)));
            }
            return ids.stream().distinct().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JSONObject parseJson(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int a = raw.indexOf('{');
            int b = raw.lastIndexOf('}');
            return a >= 0 && b > a ? JSONUtil.parseObj(raw.substring(a, b + 1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> strList(JSONArray arr) {
        if (arr == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : arr) if (o != null && StrUtil.isNotBlank(String.valueOf(o))) out.add(String.valueOf(o));
        return out;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (StrUtil.isBlank(raw)) return fallback;
        try { return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }
}
