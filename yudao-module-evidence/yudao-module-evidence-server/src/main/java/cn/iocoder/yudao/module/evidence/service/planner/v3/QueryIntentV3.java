package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Query Engine V3 的业务语义 IR。
 *
 * <p>关键约束：Planner 只描述“找谁（Selection）+ 找到后做什么（Actions）”，
 * 不暴露 BM25、Milvus、RRF 等基础设施，也不允许输出 SQL/DSL。</p>
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
    /** DETERMINISTIC_SCHEMA / LLM / LLM+LEXICAL / FAILED */
    private String plannerSource;
    private Long plannerElapsedMs;

    @Builder.Default
    private PlannerStatus plannerStatus = PlannerStatus.EXECUTABLE;

    public enum PlannerStatus {
        EXECUTABLE,
        CLARIFICATION_REQUIRED,
        FAILED
    }

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
        /**
         * STRUCTURED_FILTER 使用 FilterOperator 强类型白名单。
         * EXACT_ENTITY 的业务语义天然是精确相等，执行时固定为 EQ，不依赖模型输出该字段。
         */
        private FilterOperator operator;
        /** 仅用于边界校验和 Trace；Executor 永远不读取该原始字符串。 */
        private String operatorRaw;
        @Builder.Default
        private List<String> values = new ArrayList<>();
        /** Planner 给第一轮检索的有限改写；不是下游重新分析。 */
        @Builder.Default
        private List<String> queryVariants = new ArrayList<>();
        /** EXACT_ENTITY 可由字面事实直接带入。 */
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
        /** SUMMARIZE / ANSWER / COMPARE 的证据检索焦点。 */
        private String query;
        private Integer limit;
    }
}
