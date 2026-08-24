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

    /** 单字段兼容入口。 */
    private String fieldCode;

    /**
     * 多字段投影。非空时表示一次返回多个注册字段；fieldCode 保留第一个字段作为执行适配器锚点。
     * Executor/Adapter 只能消费 DomainFieldRegistry 已注册字段，禁止任意字段名。
     */
    @Builder.Default
    private List<String> projections = new ArrayList<>();

    private Operation operation;
    private String groupBy;
    private Map<String, String> filters;
    private SortDirection sort;
    private Integer limit;
    private List<Long> resolvedEntities;
    private boolean requiresClarification;
    private String clarificationQuestion;
}
