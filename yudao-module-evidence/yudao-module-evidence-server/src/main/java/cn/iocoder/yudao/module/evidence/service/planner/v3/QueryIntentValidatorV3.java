package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** QueryIntentV3 白名单校验；任何未注册字段、指标、运算符都禁止进入执行层。 */
@Component
public class QueryIntentValidatorV3 {

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;

    public QueryIntentValidatorV3(DomainFieldRegistry fieldRegistry, DomainMetricRegistry metricRegistry) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
    }

    public Validation validate(QueryIntentV3 intent) {
        if (intent == null) return Validation.failure("EMPTY_INTENT");
        if (intent.getPlannerStatus() == QueryIntentV3.PlannerStatus.FAILED) {
            return Validation.failure(StrUtil.blankToDefault(intent.getReasonCode(), "PLANNER_FAILED"));
        }
        boolean clarification = intent.getPlannerStatus() == QueryIntentV3.PlannerStatus.CLARIFICATION_REQUIRED
                || intent.isRequiresClarification();
        if (clarification) {
            if (intent.getPlannerStatus() != QueryIntentV3.PlannerStatus.CLARIFICATION_REQUIRED
                    || !intent.isRequiresClarification()
                    || StrUtil.isBlank(intent.getClarificationQuestion())) {
                return Validation.failure("INVALID_CLARIFICATION_CONTRACT");
            }
            return Validation.success();
        }
        if (StrUtil.isBlank(intent.getDomainCode())) return Validation.failure("MISSING_DOMAIN");
        if (intent.getSelection() == null || intent.getSelection().getType() == null) {
            return Validation.failure("MISSING_SELECTION");
        }
        if (intent.getActions() == null || intent.getActions().isEmpty()) {
            return Validation.failure("MISSING_ACTION");
        }

        Set<String> allowedFields = new HashSet<>();
        Map<String, FieldDefinition> fieldDefinitions = new HashMap<>();
        fieldRegistry.all(intent.getDomainCode()).forEach(f -> {
            if (f != null && StrUtil.isNotBlank(f.getFieldCode())) {
                String code = f.getFieldCode().toUpperCase();
                allowedFields.add(code);
                fieldDefinitions.put(code, f);
            }
        });
        Set<String> allowedMetrics = new HashSet<>();
        metricRegistry.all(intent.getDomainCode()).forEach(m -> {
            if (m != null && StrUtil.isNotBlank(m.getMetricCode())) allowedMetrics.add(m.getMetricCode().toUpperCase());
        });

        QueryIntentV3.Selection selection = intent.getSelection();
        if (selection.getType() == QueryIntentV3.SelectionType.EXACT_ENTITY) {
            if (StrUtil.isBlank(selection.getField()) || !allowedFields.contains(selection.getField().toUpperCase())) {
                return Validation.failure("INVALID_SELECTION_FIELD");
            }
            FieldDefinition field = fieldDefinitions.get(selection.getField().toUpperCase());
            if (field == null || !field.isFilterable() || !field.isExactIdentifier()) {
                return Validation.failure("NON_IDENTIFIER_EXACT_SELECTION");
            }
            // EXACT_ENTITY 的业务语义固定为精确相等。Planner 负责写入 EQ，Validator 再强制校验，
            // 防止其他内部调用绕过 Planner 后把非精确运算符带入执行层。
            if (selection.getOperator() != FilterOperator.EQ) {
                return Validation.failure("INVALID_EXACT_OPERATOR");
            }
            if (selection.getValues() == null || selection.getValues().isEmpty()) {
                return Validation.failure("MISSING_EXACT_VALUE");
            }
        }

        if (selection.getType() == QueryIntentV3.SelectionType.STRUCTURED_FILTER) {
            if (StrUtil.isBlank(selection.getField()) || !allowedFields.contains(selection.getField().toUpperCase())) {
                return Validation.failure("INVALID_SELECTION_FIELD");
            }
            if (selection.getOperator() == null) {
                return Validation.failure(StrUtil.isBlank(selection.getOperatorRaw())
                        ? "MISSING_FILTER_OPERATOR" : "INVALID_FILTER_OPERATOR");
            }
            FieldDefinition field = fieldDefinitions.get(selection.getField().toUpperCase());
            if (field == null || !field.isFilterable()) return Validation.failure("FILTER_FIELD_UNAVAILABLE");
            if (field.getAllowedOperators() == null || !field.getAllowedOperators().contains(selection.getOperator())) {
                return Validation.failure("FILTER_OPERATOR_NOT_ALLOWED_FOR_FIELD");
            }
            if (selection.getOperator() != FilterOperator.EXISTS
                    && (selection.getValues() == null || selection.getValues().isEmpty())) {
                return Validation.failure("MISSING_FILTER_VALUE");
            }
            if (selection.getOperator() == FilterOperator.BETWEEN && selection.getValues().size() != 2) {
                return Validation.failure("INVALID_BETWEEN_VALUES");
            }
        }

        if ((selection.getType() == QueryIntentV3.SelectionType.SEMANTIC
                || selection.getType() == QueryIntentV3.SelectionType.EXACT_TEXT)
                && StrUtil.isBlank(selection.getQuery())) {
            return Validation.failure("MISSING_SELECTION_QUERY");
        }

        for (QueryIntentV3.Action action : intent.getActions()) {
            if (action == null || action.getType() == null) return Validation.failure("INVALID_ACTION");
            if (action.getType() == QueryIntentV3.ActionType.PROJECT_FIELDS) {
                List<String> fields = action.getFields();
                if (fields == null || fields.isEmpty()) return Validation.failure("MISSING_PROJECTION");
                for (String field : fields) {
                    if (StrUtil.isBlank(field) || !allowedFields.contains(field.toUpperCase())) {
                        return Validation.failure("INVALID_PROJECTION_FIELD");
                    }
                }
            }
            if (action.getType() == QueryIntentV3.ActionType.AGGREGATE && StrUtil.isNotBlank(action.getMetric())
                    && !allowedMetrics.contains(action.getMetric().toUpperCase())) {
                return Validation.failure("INVALID_METRIC");
            }
        }
        return Validation.success();
    }

    public record Validation(boolean valid, String reasonCode) {
        public static Validation success() {
            return new Validation(true, null);
        }

        public static Validation failure(String reasonCode) {
            return new Validation(false, reasonCode);
        }
    }
}
