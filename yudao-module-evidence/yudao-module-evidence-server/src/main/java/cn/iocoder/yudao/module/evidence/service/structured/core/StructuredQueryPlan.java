package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Structured Query Plan(Platform Core 领域无关)。
 * <p>
 * Query Planner 的权威产出: 明确用户想对"什么对象(entityType/scope)"做"什么运算(operation/metric)"。
 * 后续统一收敛到 STRUCTURED_QUERY 路由, 不再新增 KB_AGGREGATE 等平级路由。
 */
@Data
@Builder
public class StructuredQueryPlan {

    /** 对外路由: STRUCTURED_QUERY / CLARIFY(由引擎统一填写) */
    private String route;

    /** 查询类型: EXACT_LOOKUP / AGGREGATE / LIST / GROUP / SORT / TOP_N */
    private QueryType queryType;

    /** 领域编码(如 PATENT; 由 Domain Registry 解析) */
    private String domainCode;

    /** 实体类型(如 PATENT_DOCUMENT / CLAIM; 由 Domain Entity Registry 解析) */
    private String entityType;

    /** 查询范围(必须可消解, 禁止随机选择) */
    private QueryScope scope;

    /** 指标编码(如 CLAIM_COUNT / DOCUMENT_COUNT; 由 Domain Metric Registry 解析) */
    private String metricCode;

    /** 聚合运算(COUNT / COUNT_DISTINCT / SUM / AVG / MIN / MAX / NONE) */
    private Operation operation;

    /** 分组维度(如 PATENT_DOCUMENT; 空 = 不分组) */
    private String groupBy;

    /** 过滤条件(如 publishedOnly=true / domainCode=PATENT; 白名单, 禁止任意 SQL) */
    private Map<String, String> filters;

    /** 排序方向(TOP_N / SORT 时) */
    private SortDirection sort;

    /** 排序取数上限(TOP_N 时) */
    private Integer limit;

    /** 已消解的实体集合(scope=DOCUMENT_SET/ENTITY_SET 时) */
    private List<Long> resolvedEntities;

    /** 是否无法消解 scope/metric/operation, 需要反问用户 */
    private boolean requiresClarification;

    /** 反问句(requiresClarification=true 时) */
    private String clarificationQuestion;

}
