package cn.iocoder.yudao.module.evidence.service.planner.v3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Query Engine V3 的业务语义 IR。
 *
 * <p>关键约束：Planner 只描述“找谁(Selection) + 找到后做什么(Actions)”，
 * 不暴露 BM25/Milvus/RRF 等基础设施，也不允许输出 SQL/DSL。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntentV3 {

    private String version;
    private String domainCode;
    private String entityType;
    private Selection selection;

    @Builder.Default
    private List<Action> actions = new ArrayList<>();

    /** COMPLETE_REQUIRED / BEST_EFFORT / TOP_K_ALLOWED */
    private String completeness;

    private boolean requiresClarification;
    private String clarificationQuestion;
    private String reasonCode;
    /** LLM / FALLBACK */
    private String plannerSource;
    private Long plannerElapsedMs;

    public enum SelectionType {
        CURRENT_SCOPE,
        RESULT_SET,
        EXACT_ENTITY,
        STRUCTURED_FILTER,
        SEMANTIC,
        EXACT_TEXT
    }

    public enum ActionType {
        PROJECT_FIELDS,
        LIST,
        COUNT,
        AGGREGATE,
        COMPARE,
        SUMMARIZE,
        ANSWER
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Selection {
        private SelectionType type;
        /** SEMANTIC / EXACT_TEXT 使用。 */
        private String query;
        /** STRUCTURED_FILTER / EXACT_ENTITY 使用。 */
        private String field;
        /** EQ / NE / CONTAINS / STARTS_WITH / IN / EXISTS */
        private String operator;
        @Builder.Default
        private List<String> values = new ArrayList<>();
        /** Planner 给第一轮检索的有限改写；不是下游重新分析。 */
        @Builder.Default
        private List<String> queryVariants = new ArrayList<>();
        /** EXACT_ENTITY 可由词法事实直接带入。 */
        @Builder.Default
        private List<Long> entityIds = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private ActionType type;
        @Builder.Default
        private List<String> fields = new ArrayList<>();
        private String metric;
        private String operation;
        private String compareType;
        /** SUMMARIZE/ANSWER/COMPARE 的证据检索焦点。 */
        private String query;
        private Integer limit;
    }
}
