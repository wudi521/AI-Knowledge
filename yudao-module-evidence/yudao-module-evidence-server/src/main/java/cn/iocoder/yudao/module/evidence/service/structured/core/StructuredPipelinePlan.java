package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent V1.1 的组合式结构化查询计划。
 * 固定的是数据运算原语，不固定用户问题类型；近似受控 relational pipeline。
 */
@Data
@Builder
public class StructuredPipelinePlan {
    private String domainCode;
    private String entityType;
    private QueryScope scope;

    @Builder.Default
    private List<StructuredValueExpression> select = new ArrayList<>();

    private StructuredPredicateNode filter;

    @Builder.Default
    private List<StructuredValueExpression> groupBy = new ArrayList<>();

    private StructuredAggregateSpec aggregate;

    /** GROUP BY 聚合后的类型化过滤；只作用于 aggregateValue。 */
    private StructuredHavingSpec having;

    @Builder.Default
    private List<StructuredOrderSpec> orderBy = new ArrayList<>();

    private boolean distinct;
    private Integer limit;
}
