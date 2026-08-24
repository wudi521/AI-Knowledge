package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Structured Query 编排(Platform Core 领域无关)。 */
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

    /** 可选增强：字段注入保持既有构造器源码兼容；仅 >=2 个注册字段时接管。 */
    @Resource
    private MultiFieldProjectionService multiFieldProjectionService;

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

    public enum State {
        ANSWER, CLARIFY, NOT_STRUCTURED, UNANSWERABLE, SEMANTIC
    }

    public record HandleResult(State state, StructuredQueryPlan plan,
                               MetricDefinition metric, StructuredQueryResult result,
                               String answer, String clarificationQuestion,
                               String reasonCode, List<Long> semanticEntityIds) {
    }

    public HandleResult handle(String query, Long kbId, String domainCode, List<ChatTurnDTO> history) {
        return handle(query, kbId, domainCode, history, null, null);
    }

    public HandleResult handle(String query, Long kbId, String domainCode, List<ChatTurnDTO> history,
                               List<Long> explicitEntityIds, String fieldCodeHint) {
        if (StrUtil.isBlank(query) || kbId == null) {
            return new HandleResult(State.UNANSWERABLE, null, null, null, null, null,
                    StructuredFailureReason.AMBIGUOUS_SCOPE, null);
        }
        StructuredQueryPreParser.PreParsedQuery pre = preParser.parse(query);
        if (!completenessGuard.isStructuredCandidate(query)) {
            return new HandleResult(State.NOT_STRUCTURED, null, null, null, null, null, null, null);
        }
        if (domainCode == null || metricRegistry.all(domainCode).isEmpty()) {
            return new HandleResult(State.NOT_STRUCTURED, null, null, null, null, null, null, null);
        }

        // 多字段投影必须在单 metric/field 最长匹配前执行，否则“申请号和公布号”永远只会留下一个字段。
        if (multiFieldProjectionService != null) {
            MultiFieldProjectionService.Result multi = multiFieldProjectionService.tryHandle(
                    query, kbId, domainCode, explicitEntityIds);
            if (multi.state() == MultiFieldProjectionService.State.ANSWER) {
                return new HandleResult(State.ANSWER, multi.plan(), multi.anchorMetric(), multi.result(),
                        multi.answer(), null, null, null);
            }
            if (multi.state() == MultiFieldProjectionService.State.CLARIFY) {
                return new HandleResult(State.CLARIFY, multi.plan(), multi.anchorMetric(), multi.result(),
                        null, multi.clarificationQuestion(), multi.reasonCode(), null);
            }
            if (multi.state() == MultiFieldProjectionService.State.UNANSWERABLE) {
                return new HandleResult(State.UNANSWERABLE, multi.plan(), multi.anchorMetric(), multi.result(),
                        null, null, multi.reasonCode(), null);
            }
        }

        MetricDefinition metric = resolveMetric(query, domainCode);
        FieldDefinition field = null;
        if (metric == null) {
            if (StrUtil.isNotBlank(fieldCodeHint)) {
                field = fieldRegistry.byCode(domainCode, fieldCodeHint).orElse(null);
                if (field == null) {
                    if (explicitEntityIds != null && !explicitEntityIds.isEmpty()) {
                        return semanticResult(kbId, domainCode, explicitEntityIds);
                    }
                    return clarifyResult(domainCode, "该字段暂不支持结构化查询。",
                            StructuredFailureReason.UNSUPPORTED_FIELD);
                }
            } else {
                field = fieldRegistry.findByAlias(query, domainCode).orElse(null);
            }
            if (field != null) {
                metric = fieldToMetric(field, domainCode);
                metricRegistry.register(metric);
            } else if (explicitEntityIds != null && !explicitEntityIds.isEmpty()) {
                return semanticResult(kbId, domainCode, explicitEntityIds);
            } else {
                return clarifyResult(domainCode, buildMetricClarify(domainCode),
                        StructuredFailureReason.MISSING_METRIC);
            }
        }

        if (field == null && isCrossEntitySemanticCandidate(query)) {
            return crossEntityResult(kbId, domainCode);
        }

        Operation op = resolveOperation(query, metric);
        QueryType queryType = resolveQueryType(query, pre, op);
        if (field != null) queryType = QueryType.LIST;
        if (op != Operation.NONE && !metric.getSupportedOperations().contains(op)) {
            return clarifyResult(domainCode, "该指标不支持“" + op + "”运算，请换一种问法。",
                    StructuredFailureReason.UNSUPPORTED_OPERATION);
        }

        QueryScope scope;
        if (queryType == QueryType.TOP_N) {
            scope = QueryScope.currentKb(kbId);
        } else if (explicitEntityIds != null && !explicitEntityIds.isEmpty()) {
            scope = QueryScope.documentSet(kbId, explicitEntityIds);
        } else {
            StructuredQueryContextResolver.ScopeResolution sr = contextResolver.resolve(pre, domainCode, kbId, history);
            if (sr.clarified()) {
                return clarifyResult(domainCode, sr.clarificationQuestion(), StructuredFailureReason.AMBIGUOUS_SCOPE);
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

        StructuredQueryResult result = executor.execute(plan);
        if (result == null || result.isUnsupported()) {
            log.warn("[handle][query({}) 结构化执行不支持: {}]", query,
                    result == null ? "null" : result.getUnsupportedReason());
            return new HandleResult(State.UNANSWERABLE, plan, metric, result, null, null,
                    reasonForUnsupported(result), null);
        }

        EntityDefinition entity = entityRegistry.lookup(domainCode, metric.getEntityType()).orElse(null);
        String answer = renderer.render(plan, metric, entity, result);
        if (StrUtil.isBlank(answer)) {
            return new HandleResult(State.UNANSWERABLE, plan, metric, result, null, null,
                    StructuredFailureReason.EMPTY_RESULT_SET, null);
        }
        return new HandleResult(State.ANSWER, plan, metric, result, answer, null, null, null);
    }

    private HandleResult semanticResult(Long kbId, String domainCode, List<Long> entityIds) {
        QueryScope scope = QueryScope.documentSet(kbId, entityIds);
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("PER_ENTITY_SEMANTIC")
                .domainCode(domainCode)
                .scope(scope)
                .resolvedEntities(entityIds)
                .build();
        return new HandleResult(State.SEMANTIC, plan, null, null, null, null,
                StructuredFailureReason.MISSING_METRIC, entityIds);
    }

    private HandleResult crossEntityResult(Long kbId, String domainCode) {
        QueryScope scope = QueryScope.currentKb(kbId);
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("CROSS_ENTITY_SEMANTIC")
                .domainCode(domainCode)
                .scope(scope)
                .build();
        return new HandleResult(State.SEMANTIC, plan, null, null, null, null,
                StructuredFailureReason.MISSING_METRIC, null);
    }

    private boolean isCrossEntitySemanticCandidate(String query) {
        if (StrUtil.isBlank(query)) return false;
        boolean listIntent = StrUtil.containsAny(query, "有哪些", "哪些", "列举", "列出");
        boolean kbScope = StrUtil.containsAny(query, "知识库", "当前库", "库中", "库里面", "里面", "当中");
        boolean semanticCondition = StrUtil.containsAny(query, "支持", "提到", "涉及", "采用", "关于", "具备",
                "包含", "具有", "适用于", "能不能", "是否", "有什么");
        return listIntent && kbScope && semanticCondition;
    }

    private String reasonForUnsupported(StructuredQueryResult result) {
        if (result == null || result.getUnsupportedReason() == null) return StructuredFailureReason.EMPTY_RESULT_SET;
        String reason = result.getUnsupportedReason();
        if (reason.contains("运算不支持")) return StructuredFailureReason.UNSUPPORTED_OPERATION;
        if (reason.contains("指标未注册") || reason.contains("指标未解析")) return StructuredFailureReason.MISSING_METRIC;
        if (reason.contains("字段暂无可结构化") || reason.contains("字段") && reason.contains("不支持")) {
            return StructuredFailureReason.UNSUPPORTED_FIELD;
        }
        if (reason.contains("scope 未确定")) return StructuredFailureReason.AMBIGUOUS_SCOPE;
        return StructuredFailureReason.EMPTY_RESULT_SET;
    }

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

    private MetricDefinition resolveMetric(String query, String domainCode) {
        Collection<MetricDefinition> metrics = metricRegistry.all(domainCode);
        MetricDefinition aliasBest = bestMatch(query, metrics, true);
        if (aliasBest != null) return aliasBest;
        MetricDefinition displayBest = bestMatch(query, metrics, false);
        if (displayBest != null) return displayBest;
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

    private MetricDefinition bestMatch(String query, Collection<MetricDefinition> metrics, boolean aliasesOnly) {
        MetricDefinition best = null;
        int bestLen = 0;
        for (MetricDefinition m : metrics) {
            List<String> candidates = new ArrayList<>();
            if (aliasesOnly) {
                if (m.getAliases() != null) candidates.addAll(m.getAliases());
            } else if (StrUtil.isNotBlank(m.getDisplayName())) {
                candidates.add(m.getDisplayName());
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
            if (entityCode.equals(m.getEntityType()) && m.getSupportedOperations() != null
                    && (m.getSupportedOperations().contains(Operation.COUNT)
                    || m.getSupportedOperations().contains(Operation.COUNT_DISTINCT))) return m;
        }
        return null;
    }

    private boolean mentionsEntity(String query, EntityDefinition e) {
        if (StrUtil.isNotBlank(e.getDisplayLabel()) && query.contains(e.getDisplayLabel())) return true;
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) if (a != null && query.contains(a)) return true;
        }
        return false;
    }

    private boolean hasCountWord(String query) {
        return StrUtil.containsAny(query, "几个", "多少", "数量", "总数", "共有", "一共", "总共", "合计");
    }

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
        if (StrUtil.containsAny(query, "分别")) return Operation.NONE;
        if (StrUtil.containsAny(query, "共有", "总共", "一共", "合计", "总共有", "一共有")) {
            if (supportedSum) return Operation.SUM;
            if (supportedCount) return Operation.COUNT;
            return Operation.NONE;
        }
        if (supportedCount) return Operation.COUNT;
        if (supportedSum) return Operation.SUM;
        return Operation.NONE;
    }

    private QueryType resolveQueryType(String query, StructuredQueryPreParser.PreParsedQuery pre, Operation op) {
        boolean topN = pre.isSortIntent() && (pre.getCardinality() != null
                || StrUtil.containsAny(query, "排名", "哪几个", "是哪几个", "哪些", "前"));
        if (topN) return QueryType.TOP_N;
        if (StrUtil.containsAny(query, "分别")) return QueryType.GROUP;
        if (StrUtil.containsAny(query, "有哪些", "列举", "列出", "分别是哪些", "分别是什么")) return QueryType.LIST;
        return QueryType.AGGREGATE;
    }

    private SortDirection resolveSort(String query, Operation op, QueryType queryType) {
        if (queryType == QueryType.TOP_N) {
            return StrUtil.containsAny(query, "最少", "最小", "最低") ? SortDirection.ASC : SortDirection.DESC;
        }
        return null;
    }

    private Integer resolveLimit(String query, StructuredQueryPreParser.PreParsedQuery pre, QueryType queryType) {
        if (queryType != QueryType.TOP_N) return null;
        if (pre.getCardinality() != null) return pre.getCardinality();
        return 3;
    }

    private HandleResult clarifyResult(String domainCode, String question, String reasonCode) {
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("CLARIFY").domainCode(domainCode).requiresClarification(true)
                .clarificationQuestion(question).build();
        return new HandleResult(State.CLARIFY, plan, null, null, null, question, reasonCode, null);
    }

    private String buildMetricClarify(String domainCode) {
        List<String> names = metricRegistry.all(domainCode).stream()
                .map(MetricDefinition::getDisplayName).filter(StrUtil::isNotBlank).toList();
        return "你希望统计或比较哪个指标？例如：" + String.join("、", names) + "。";
    }
}
