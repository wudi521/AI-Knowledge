package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Structured Query Plan(Platform Core 领域无关)。 */
@Data
@Builder
public class StructuredQueryPlan {

    private String route;
    private QueryType queryType;
    private String domainCode;
    private String entityType;
    private QueryScope scope;
    private String metricCode;
    private String fieldCode;

    @Builder.Default
    private List<String> projections = new ArrayList<>();

    private Operation operation;
    private String groupBy;

    /** 旧等值过滤兼容字段；新代码优先使用 filterExpression。 */
    private Map<String, String> filters;

    /** 类型化 AND/OR Filter Tree；只允许白名单字段与 FilterOperator。 */
    private FilterExpression filterExpression;

    private SortDirection sort;
    private Integer limit;
    private List<Long> resolvedEntities;
    private boolean requiresClarification;
    private String clarificationQuestion;
}
