package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Structured Query 编排(Platform Core 领域无关)。
 * <p>
 * 两级 Planner:
 * <ul>
 *     <li>Level-1 {@link StructuredQueryPreParser} 确定性识别候选信号(0 LLM);</li>
 *     <li>Level-2 语义消解: metric 由 {@link DomainMetricRegistry} 同义词解析, scope 由
 *         {@link StructuredQueryContextResolver} 结合历史消解; 仍无法消解 → CLARIFY(禁止猜测/随机)。</li>
 * </ul>
 * <p>
 * 关键约束:
 * <ul>
 *     <li>关键词只用于判断 candidate, 不直接决定 COUNT DOCUMENT;</li>
 *     <li>执行只基于完整结构化数据集, 禁止 TopK 计算全集;</li>
 *     <li>答案由 {@link StructuredAnswerRenderer} 确定性生成, 不调 LLM 复述数值。</li>
 * </ul>
 */
@Slf4j
@Component
public class StructuredQueryService {

    private final StructuredQueryPreParser preParser;
    private final DomainMetricRegistry metricRegistry;
    private final DomainEntityRegistry entityRegistry;
    private final DomainFieldRegistry fieldRegistry;
    private final StructuredQueryContextResolver contextResolver;
    private final StructuredQueryExecutor executor;
    private final StructuredAnswerRenderer renderer;
    private final CompletenessGuard completenessGuard;

    public StructuredQueryService(StructuredQueryPreParser preParser,
                                  DomainMetricRegistry metricRegistry,
                                  DomainEntityRegistry entityRegistry,
                                  DomainFieldRegistry fieldRegistry,
                                  StructuredQueryContextResolver contextResolver,
                                  StructuredQueryExecutor executor,
                                  StructuredAnswerRenderer renderer,
                                  CompletenessGuard completenessGuard) {
        this.preParser = preParser;
        this.metricRegistry = metricRegistry;
        this.entityRegistry = entityRegistry;
        this.fieldRegistry = fieldRegistry;
        this.contextResolver = contextResolver;
        this.executor = executor;
        this.renderer = renderer;
        this.completenessGuard = completenessGuard;
    }

    /** 处理结果状态 */
    public enum State {
        /** 已确定性回答 */
        ANSWER,
        /** 需要反问 */
        CLARIFY,
        /** 非结构化查询(交还 RAG 路径) */
        NOT_STRUCTURED,
        /** 结构化但不可作答(数据源不支持/指标未注册/数据集不完整) */
        UNANSWERABLE
    }

    /** 处理结果 */
    public record HandleResult(State state, StructuredQueryPlan plan,
                               MetricDefinition metric, StructuredQueryResult result,
                               String answer, String clarificationQuestion) {
    }

    /**
     * 处理结构化候选查询。
     *
     * @param query     用户问题
     * @param kbId      当前知识库(结构化路径要求单库; 空/多库 → UNANSWERABLE 由调用方拒绝)
     * @param domainCode 知识库领域(如 PATENT; 决定用哪个 Domain Registry)
     * @param history   会话历史(范围指代消解用)
     */
    public HandleResult handle(String query, Long kbId, String domainCode, List<ChatTurnDTO> history) {
        return handle(query, kbId, domainCode, history, null, null);
    }

    /**
     * 多轮增强(CQ-04~10): chat 侧已消解历史结果集时, 传入 explicitEntityIds(scope=DOCUMENT_SET)
     * 与 fieldCodeHint(已继承/解析的字段), 避免无历史时"这些/它们"无法消解范围。
     */
    public HandleResult handle(String query, Long kbId, String domainCode, List<ChatTurnDTO> history,
                               List<Long> explicitEntityIds, String fieldCodeHint) {
        if (StrUtil.isBlank(query) || kbId == null) {
            return new HandleResult(State.UNANSWERABLE, null, null, null, null, null);
        }
        // Level-1: 确定性候选信号
        StructuredQueryPreParser.PreParsedQuery pre = preParser.parse(query);
        if (!completenessGuard.isStructuredCandidate(query)) {
            return new HandleResult(State.NOT_STRUCTURED, null, null, null, null, null);
        }
        // 领域未注册(非结构化领域且无 Domain Pack) → 交还 RAG 或拒答
        if (domainCode == null || metricRegistry.all(domainCode).isEmpty()) {
            return new HandleResult(State.NOT_STRUCTURED, null, null, null, null, null);
        }

        // Level-2: metric 解析(Registry 同义词, 禁止硬编码业务词)
        MetricDefinition metric = resolveMetric(query, domainCode);
        // CQ-12/15: metric 未命中但命中 Field(公布号/申请号等维度字段) → 字段 LIST(每实体一值), 非聚合
        FieldDefinition field = null;
        if (metric == null) {
            field = fieldCodeHint != null
                    ? fieldRegistry.byCode(domainCode, fieldCodeHint).orElse(null)
                    : fieldRegistry.findByAlias(query, domainCode).orElse(null);
            if (field != null) {
                metric = fieldToMetric(field, domainCode);
                metricRegistry.register(metric); // 供 Executor 按 metricCode 查找字段适配器
            } else {
                return clarifyResult(domainCode, buildMetricClarify(domainCode));
            }
        }

        // 运算/查询类型解析
        Operation op = resolveOperation(query, metric);
        QueryType queryType = resolveQueryType(query, pre, op);
        // CQ-12: 字段(维度)查询不可聚合, 一律 LIST(每实体一值)
        if (field != null) {
            queryType = QueryType.LIST;
        }
        if (op != Operation.NONE && !metric.getSupportedOperations().contains(op)) {
            return clarifyResult(domainCode, "该指标不支持“" + op + "”运算，请换一种问法。");
        }

        // 范围解析(TOP_N 的数量词是 limit, 不是范围对象)
        QueryScope scope;
        if (queryType == QueryType.TOP_N) {
            scope = QueryScope.currentKb(kbId);
        } else if (explicitEntityIds != null && !explicitEntityIds.isEmpty()) {
            // CQ-04~10: chat 侧已消解历史结果集 → 直接作为 DOCUMENT_SET 范围
            scope = QueryScope.documentSet(kbId, explicitEntityIds);
        } else {
            StructuredQueryContextResolver.ScopeResolution sr =
                    contextResolver.resolve(pre, domainCode, kbId, history);
            if (sr.clarified()) {
                return clarifyResult(domainCode, sr.clarificationQuestion());
            }
            scope = sr.scope();
        }

        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY")
                .queryType(queryType)
                .domainCode(domainCode)
                .entityType(metric.getEntityType())
                .scope(scope)
                .metricCode(metric.getMetricCode())
                .fieldCode(field != null ? field.getFieldCode() : null)
                .operation(op)
                .groupBy(queryType == QueryType.GROUP ? metric.getEntityType() : null)
                .filters(java.util.Map.of("publishedOnly", "true"))
                .sort(resolveSort(query, op, queryType))
                .limit(resolveLimit(query, pre, queryType))
                .resolvedEntities(scope.getResolvedEntityIds())
                .build();

        // 执行(完整结构化数据集; 非 TopK)
        StructuredQueryResult result = executor.execute(plan);
        if (result == null || result.isUnsupported()) {
            log.warn("[handle][query({}) 结构化执行不支持: {}]", query,
                    result == null ? "null" : result.getUnsupportedReason());
            return new HandleResult(State.UNANSWERABLE, plan, metric, result, null, null);
        }

        EntityDefinition entity = entityRegistry.lookup(domainCode, metric.getEntityType()).orElse(null);
        String answer = renderer.render(plan, metric, entity, result);
        if (StrUtil.isBlank(answer)) {
            return new HandleResult(State.UNANSWERABLE, plan, metric, result, null, null);
        }
        return new HandleResult(State.ANSWER, plan, metric, result, answer, null);
    }

    /** 字段解析回退: Field → 合成 MetricDefinition(承载 fieldCode, 无聚合运算), CQ-11/12 */
    private MetricDefinition fieldToMetric(FieldDefinition field, String domainCode) {
        return MetricDefinition.builder()
                .metricCode(field.getFieldCode())
                .domainCode(domainCode)
                .entityType(field.getEntityType())
                .valueType(field.getValueType())
                .supportedOperations(java.util.Set.of())
                .adapterKey(domainCode)
                .aliases(field.getAliases())
                .build();
    }

    /** 指标解析: ①显式别名最长匹配 → ②displayName 匹配 → ③实体计数回退(如 "几个专利" → DOCUMENT_COUNT) */
    private MetricDefinition resolveMetric(String query, String domainCode) {
        Collection<MetricDefinition> metrics = metricRegistry.all(domainCode);
        // ① 显式别名(同义词)最长匹配(度量别名优先, 如 "价格" 优先于实体名 "产品")
        MetricDefinition aliasBest = bestMatch(query, metrics, true);
        if (aliasBest != null) return aliasBest;
        // ② displayName 匹配(如 "有几个专利文献" → DOCUMENT_COUNT)
        MetricDefinition displayBest = bestMatch(query, metrics, false);
        if (displayBest != null) return displayBest;
        // ③ 实体计数回退: "几个/多少/数量" + 实体同义词 → 该实体对应的计数指标
        if (hasCountWord(query)) {
            for (EntityDefinition e : entityRegistry.all(domainCode)) {
                if (mentionsEntity(query, e)) {
                    MetricDefinition countMetric = countMetricForEntity(domainCode, e.getEntityCode());
                    if (countMetric != null) return countMetric;
                }
            }
        }
        return null;
    }

    /** 在指标候选词(别名或 displayName)中做最长匹配; tie 保持先到先得 */
    private MetricDefinition bestMatch(String query, Collection<MetricDefinition> metrics, boolean aliasesOnly) {
        MetricDefinition best = null;
        int bestLen = 0;
        for (MetricDefinition m : metrics) {
            List<String> candidates = new ArrayList<>();
            if (aliasesOnly) {
                if (m.getAliases() != null) candidates.addAll(m.getAliases());
            } else {
                if (StrUtil.isNotBlank(m.getDisplayName())) candidates.add(m.getDisplayName());
            }
            for (String alias : candidates) {
                if (alias != null && query.contains(alias) && alias.length() > bestLen) {
                    best = m;
                    bestLen = alias.length();
                }
            }
        }
        return best;
    }

    private MetricDefinition countMetricForEntity(String domainCode, String entityCode) {
        for (MetricDefinition m : metricRegistry.all(domainCode)) {
            if (entityCode.equals(m.getEntityType())
                    && m.getSupportedOperations() != null
                    && (m.getSupportedOperations().contains(Operation.COUNT)
                    || m.getSupportedOperations().contains(Operation.COUNT_DISTINCT))) {
                return m;
            }
        }
        return null;
    }

    private boolean mentionsEntity(String query, EntityDefinition e) {
        if (StrUtil.isNotBlank(e.getDisplayLabel()) && query.contains(e.getDisplayLabel())) return true;
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) {
                if (a != null && query.contains(a)) return true;
            }
        }
        return false;
    }

    private boolean hasCountWord(String query) {
        return StrUtil.containsAny(query, "几个", "多少", "数量", "总数", "共有", "一共", "总共", "合计");
    }

    /** 运算解析(领域无关; 与 metric.supportedOperations 联合校验) */
    private Operation resolveOperation(String query, MetricDefinition metric) {
        boolean supportedSum = metric.getSupportedOperations().contains(Operation.SUM);
        boolean supportedAvg = metric.getSupportedOperations().contains(Operation.AVG);
        boolean supportedMinMax = metric.getSupportedOperations().contains(Operation.MIN)
                || metric.getSupportedOperations().contains(Operation.MAX);
        boolean supportedCount = metric.getSupportedOperations().contains(Operation.COUNT)
                || metric.getSupportedOperations().contains(Operation.COUNT_DISTINCT);

        if (StrUtil.containsAny(query, "平均")) return supportedAvg ? Operation.AVG : Operation.NONE;
        if (StrUtil.containsAny(query, "最多", "最大", "最高")) return supportedMinMax ? Operation.MAX : Operation.NONE;
        if (StrUtil.containsAny(query, "最少", "最小", "最低")) return supportedMinMax ? Operation.MIN : Operation.NONE;
        if (StrUtil.containsAny(query, "分别")) return Operation.NONE; // GROUP/LIST, 非聚合
        if (StrUtil.containsAny(query, "共有", "总共", "一共", "合计", "总共有", "一共有")) {
            if (supportedSum) return Operation.SUM;
            if (supportedCount) return Operation.COUNT;
            return Operation.NONE;
        }
        if (supportedCount) return Operation.COUNT;
        if (supportedSum) return Operation.SUM;
        return Operation.NONE;
    }

    /** 查询类型解析(领域无关) */
    private QueryType resolveQueryType(String query, StructuredQueryPreParser.PreParsedQuery pre,
                                       Operation op) {
        boolean topN = pre.isSortIntent() && (pre.getCardinality() != null
                || StrUtil.containsAny(query, "排名", "哪几个", "是哪几个", "哪些", "前"));
        if (topN) return QueryType.TOP_N;
        if (StrUtil.containsAny(query, "分别")) return QueryType.GROUP;
        if (StrUtil.containsAny(query, "有哪些", "列举", "列出", "分别是哪些", "分别是什么")) {
            return QueryType.LIST;
        }
        return QueryType.AGGREGATE;
    }

    private SortDirection resolveSort(String query, Operation op, QueryType queryType) {
        if (queryType == QueryType.TOP_N) {
            return StrUtil.containsAny(query, "最少", "最小", "最低") ? SortDirection.ASC : SortDirection.DESC;
        }
        return null;
    }

    private Integer resolveLimit(String query, StructuredQueryPreParser.PreParsedQuery pre,
                                 QueryType queryType) {
        if (queryType != QueryType.TOP_N) return null;
        if (pre.getCardinality() != null) return pre.getCardinality();
        return 3;
    }

    private HandleResult clarifyResult(String domainCode, String question) {
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("CLARIFY")
                .domainCode(domainCode)
                .requiresClarification(true)
                .clarificationQuestion(question)
                .build();
        return new HandleResult(State.CLARIFY, plan, null, null, null, question);
    }

    private String buildMetricClarify(String domainCode) {
        List<String> names = metricRegistry.all(domainCode).stream()
                .map(MetricDefinition::getDisplayName).filter(StrUtil::isNotBlank).toList();
        return "你希望统计或比较哪个指标？例如：" + String.join("、", names) + "。";
    }
}
