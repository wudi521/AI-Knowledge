package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;

import java.util.List;

/**
 * Composite Query Plan(CQ-02/38): 单次查询的执行计划描述。
 * <p>
 * 最小 DAG: ResolveScope → StructuredLookup → Filter → Aggregate → PerEntitySemantic → Synthesis。
 * 单步结构化查询(COUNT/SUM/LIST/TOP_N)天然走 ResolveScope→StructuredLookup→Aggregate;
 * 无法结构化但有明确实体集时追加 PerEntitySemantic(逐实体 SCOPED_RAG)→Synthesis。
 * 执行由 {@code CompositeQueryExecutor} 编排, 受 planBudget(步骤/实体/模型调用/deadline)约束。
 */
public class CompositeQueryPlan {

    /** 计划步骤类型(最小 DAG 节点) */
    public enum StepType {
        RESOLVE_SCOPE, STRUCTURED_LOOKUP, FILTER, AGGREGATE, PER_ENTITY_SEMANTIC, SYNTHESIS
    }

    /** 计划预算(执行上限; null 字段用默认值兜底) */
    public record Budget(int maxSteps, int maxEntities, int maxModelCalls, long deadlineMs) {

        public static Budget defaults() {
            return new Budget(5, 100, 12, 60_000L);
        }

        public static Budget of(QueryPlanBudgetDTO dto) {
            if (dto == null) {
                return defaults();
            }
            Budget d = defaults();
            int maxSteps = dto.getMaxSteps() != null && dto.getMaxSteps() > 0 ? dto.getMaxSteps() : d.maxSteps;
            int maxEntities = dto.getMaxEntities() != null && dto.getMaxEntities() > 0 ? dto.getMaxEntities() : d.maxEntities;
            int maxModelCalls = dto.getMaxModelCalls() != null && dto.getMaxModelCalls() > 0 ? dto.getMaxModelCalls() : d.maxModelCalls;
            long deadlineMs = dto.getDeadlineMs() != null && dto.getDeadlineMs() > 0 ? dto.getDeadlineMs() : d.deadlineMs;
            return new Budget(maxSteps, maxEntities, maxModelCalls, deadlineMs);
        }
    }

    private final String queryId;
    private final String query;
    private final Long kbId;
    private final String domainCode;
    private final List<StepType> steps;
    private final Budget budget;

    public CompositeQueryPlan(String queryId, String query, Long kbId, String domainCode,
                              List<StepType> steps, Budget budget) {
        this.queryId = queryId;
        this.query = query;
        this.kbId = kbId;
        this.domainCode = domainCode;
        this.steps = steps;
        this.budget = budget;
    }

    public String getQueryId() {
        return queryId;
    }

    public String getQuery() {
        return query;
    }

    public Long getKbId() {
        return kbId;
    }

    public String getDomainCode() {
        return domainCode;
    }

    public List<StepType> getSteps() {
        return steps;
    }

    public Budget getBudget() {
        return budget;
    }
}
