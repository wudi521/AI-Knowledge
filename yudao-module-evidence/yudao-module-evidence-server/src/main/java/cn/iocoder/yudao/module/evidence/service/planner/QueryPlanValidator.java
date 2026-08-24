package cn.iocoder.yudao.module.evidence.service.planner;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import org.springframework.stereotype.Component;

/** QueryPlan 白名单校验。LLM 只负责理解，不能越过 Registry/预算直接执行。 */
@Component
public class QueryPlanValidator {

    private final DomainFieldRegistry fieldRegistry;
    private final DomainMetricRegistry metricRegistry;

    public QueryPlanValidator(DomainFieldRegistry fieldRegistry, DomainMetricRegistry metricRegistry) {
        this.fieldRegistry = fieldRegistry;
        this.metricRegistry = metricRegistry;
    }

    public Validation validate(QueryPlan plan) {
        if (plan == null || plan.getQueryClass() == null) {
            return Validation.failure("INVALID_PLAN");
        }
        if (plan.getQueryClass() == QueryClass.CLARIFY || plan.getQueryClass() == QueryClass.ABSTAIN) {
            return Validation.success();
        }
        if (plan.getExecutionMode() == null) {
            return Validation.failure("MISSING_EXECUTION_MODE");
        }
        if (plan.getExecutionMode() == ExecutionMode.EXACT_TEXT_SEARCH && StrUtil.isBlank(plan.getExactText())) {
            return Validation.failure("MISSING_EXACT_TEXT");
        }
        String domain = plan.getDomainCode();
        if (domain != null) {
            if (plan.getProjections() != null) {
                for (String field : plan.getProjections()) {
                    if (field != null && fieldRegistry.byCode(domain, field).isEmpty()) {
                        return Validation.failure("UNSUPPORTED_FIELD:" + field);
                    }
                }
            }
            if (plan.getMetrics() != null) {
                for (String metric : plan.getMetrics()) {
                    boolean exists = metricRegistry.all(domain).stream()
                            .anyMatch(m -> metric != null && metric.equals(m.getMetricCode()));
                    if (!exists) {
                        return Validation.failure("UNSUPPORTED_METRIC:" + metric);
                    }
                }
            }
        }
        if (plan.getExecutionMode() == ExecutionMode.CROSS_ENTITY_COMPARE) {
            if (plan.getComparisonType() == null || plan.getComparisonType() == ComparisonType.NONE) {
                return Validation.failure("MISSING_COMPARISON_TYPE");
            }
            if (plan.getEntityIds() != null && plan.getEntityIds().stream().distinct().count() == 1) {
                return Validation.failure("INSUFFICIENT_COMPARE_ENTITIES");
            }
        }
        if (plan.getSteps() != null && plan.getSteps().size() > 5) {
            return Validation.failure("PLAN_TOO_MANY_STEPS");
        }
        return Validation.success();
    }

    /**
     * record 会自动生成实例访问器 valid()；静态工厂不能再叫 valid()，否则 Java 方法签名冲突。
     */
    public record Validation(boolean valid, String reasonCode) {
        public static Validation success() {
            return new Validation(true, null);
        }

        public static Validation failure(String reason) {
            return new Validation(false, reason);
        }
    }
}
