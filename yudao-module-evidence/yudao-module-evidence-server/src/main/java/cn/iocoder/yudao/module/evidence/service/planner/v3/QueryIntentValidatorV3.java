package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
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
        if (intent.isRequiresClarification()) return Validation.success();
        if (StrUtil.isBlank(intent.getDomainCode())) return Validation.failure("MISSING_DOMAIN");
        if (intent.getSelection() == null || intent.getSelection().getType() == null) {
            return Validation.failure("MISSING_SELECTION");
        }
        if (intent.getActions() == null || intent.getActions().isEmpty()) {
            return Validation.failure("MISSING_ACTION");
        }

        Set<String> allowedFields = new HashSet<>();
        fieldRegistry.all(intent.getDomainCode()).forEach(f -> {
            if (f != null && StrUtil.isNotBlank(f.getFieldCode())) allowedFields.add(f.getFieldCode().toUpperCase());
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
            // EXACT_ENTITY 的业务语义固定为精确相等。Planner 负责写入 EQ，Validator 再强制校验，
            // 防止其他内部调用绕过 Planner 后把非精确运算符带入执行层。
            if (!"EQ".equalsIgnoreCase(selection.getOperator())) {
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
            if (StrUtil.isBlank(selection.getOperator())) return Validation.failure("MISSING_FILTER_OPERATOR");
            try {
                FilterOperator.valueOf(selection.getOperator().toUpperCase());
            } catch (Exception e) {
                return Validation.failure("INVALID_FILTER_OPERATOR");
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
