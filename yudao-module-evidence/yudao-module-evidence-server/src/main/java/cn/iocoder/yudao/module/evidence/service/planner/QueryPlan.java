package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Query Planner V2 权威计划。Planner 只生成白名单类型/字段/操作，Executor 不接受任意 SQL。 */
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

    @Builder.Default
    private List<String> projections = new ArrayList<>();

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

    /** EXACT_TEXT_SEARCH 专用：Planner 抽取出的目标原文短语，不包含用户指令词。 */
    private String exactText;

    private Integer perEntityTopK;
    private Boolean requireDistinctEntities;
    private String coveragePolicy;

    @Builder.Default
    private List<QueryPlan> steps = new ArrayList<>();

    private boolean requiresClarification;
    private String clarificationQuestion;
    private String reasonCode;

    /** Planner 来源：DETERMINISTIC / LLM / FALLBACK。 */
    private String plannerSource;
}
