package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical Query IR V1：Planner 的声明式结构化计划在执行前统一编译到这里。
 *
 * <p>固定的是可组合的数据运算语言，不固定用户问题类型。COUNT/AVG/GROUP_BY/ORDER_BY 等属于
 * Query IR 的有限执行原语，不是用户意图枚举；新的自然语言语义只要能由这些原语组合表达，
 * 就不应增加新的 intent/task/业务分支。</p>
 */
@Data
@Builder
public class StructuredPipelinePlan {
    public static final String IR_VERSION = "QUERY_IR_V1";

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
