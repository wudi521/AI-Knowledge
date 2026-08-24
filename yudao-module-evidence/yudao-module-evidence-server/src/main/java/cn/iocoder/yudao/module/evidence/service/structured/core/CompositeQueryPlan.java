package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;

import java.util.List;

/**
 * Composite Query Plan：有限步骤、有限实体、有限模型预算，禁止把普通查询演化成无界 Agent。
 */
public class CompositeQueryPlan {

    public enum StepType {
        RESOLVE_SCOPE, STRUCTURED_LOOKUP, FILTER, AGGREGATE, PER_ENTITY_SEMANTIC, SYNTHESIS
    }

    public record Budget(int maxSteps, int maxEntities, int maxModelCalls, long deadlineMs) {

        /**
         * P0 默认预算：最多 5 步、10 个语义实体、2 次模型预算、20 秒总 deadline。
         * Structured/ExactText 为 0 LLM；PerEntity/Compare 按实体检索后只做一次全局综合生成。
         */
        public static Budget defaults() {
            return new Budget(5, 10, 2, 20_000L);
        }

        public static Budget of(QueryPlanBudgetDTO dto) {
            if (dto == null) return defaults();
            Budget d = defaults();
            int maxSteps = positive(dto.getMaxSteps(), d.maxSteps, 1, 8);
            int maxEntities = positive(dto.getMaxEntities(), d.maxEntities, 1, 50);
            int maxModelCalls = positive(dto.getMaxModelCalls(), d.maxModelCalls, 1, 4);
            long deadlineMs = positive(dto.getDeadlineMs(), d.deadlineMs, 1_000L, 60_000L);
            return new Budget(maxSteps, maxEntities, maxModelCalls, deadlineMs);
        }

        private static int positive(Integer value, int fallback, int min, int max) {
            if (value == null || value <= 0) return fallback;
            return Math.max(min, Math.min(max, value));
        }

        private static long positive(Long value, long fallback, long min, long max) {
            if (value == null || value <= 0) return fallback;
            return Math.max(min, Math.min(max, value));
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

    public String getQueryId() { return queryId; }
    public String getQuery() { return query; }
    public Long getKbId() { return kbId; }
    public String getDomainCode() { return domainCode; }
    public List<StepType> getSteps() { return steps; }
    public Budget getBudget() { return budget; }
}
