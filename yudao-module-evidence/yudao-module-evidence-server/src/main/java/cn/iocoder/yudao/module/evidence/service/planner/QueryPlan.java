package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Query Planner V2 权威计划。Planner 只生成白名单类型/字段/操作，Executor 不接受任意 SQL。
 */
@Data
@Builder
public class QueryPlan {

    private QueryClass queryClass;
    private ExecutionMode executionMode;
    private String domainCode;
    private String entityType;

    /** CURRENT_KB / PREVIOUS_RESULT_SET / SINGLE_ENTITY / ENTITY_SET / FILTERED_SET */
    private String scopeType;
    private List<Long> entityIds;

    /** 字段投影，支持“一次列申请号、公布号和申请人”。 */
    @Builder.Default
    private List<String> projections = new ArrayList<>();

    /** 指标编码，可支持多指标计划。 */
    @Builder.Default
    private List<String> metrics = new ArrayList<>();

    private Operation operation;
    private String groupBy;
    private Map<String, Object> filters;
    private String sortBy;
    private String sortDirection;
    private Integer limit;

    private ComparisonType comparisonType;
    private Long anchorEntityId;
    private CompletenessPolicy completenessPolicy;

    /** 语义检索策略。 */
    private Integer perEntityTopK;
    private Boolean requireDistinctEntities;
    private String coveragePolicy;

    /** 复杂问题允许有限步骤组合，不做开放式 Agent。 */
    @Builder.Default
    private List<QueryPlan> steps = new ArrayList<>();

    private boolean requiresClarification;
    private String clarificationQuestion;
    private String reasonCode;

    /** Planner 来源：DETERMINISTIC / LLM / FALLBACK。 */
    private String plannerSource;
}
