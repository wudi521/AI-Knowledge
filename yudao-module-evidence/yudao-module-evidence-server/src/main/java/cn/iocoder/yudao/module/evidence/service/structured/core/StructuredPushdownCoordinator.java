package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.springframework.stereotype.Component;

import java.util.List;

/** 领域无关的整计划下推选择器；不包含任何 PATENT/CONTRACT 分支。 */
@Component
public class StructuredPushdownCoordinator {

    private final List<StructuredPushdownAdapter> adapters;

    public StructuredPushdownCoordinator(List<StructuredPushdownAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    public StructuredPushdownResult execute(StructuredPipelinePlan plan) {
        if (plan == null || plan.getDomainCode() == null) {
            return StructuredPushdownResult.unsupported("pushdown plan/domain is missing");
        }
        for (StructuredPushdownAdapter adapter : adapters) {
            if (adapter == null || !plan.getDomainCode().equalsIgnoreCase(adapter.domainCode())) continue;
            boolean supported;
            try {
                supported = adapter.supports(plan);
            } catch (Exception e) {
                return StructuredPushdownResult.failed("pushdown capability check failed: " + e.getClass().getSimpleName());
            }
            if (!supported) continue;
            try {
                StructuredPushdownResult result = adapter.executePushdown(plan);
                return result == null ? StructuredPushdownResult.failed("pushdown adapter returned null") : result;
            } catch (Exception e) {
                return StructuredPushdownResult.failed("pushdown execution failed: " + e.getClass().getSimpleName());
            }
        }
        return StructuredPushdownResult.unsupported("no pushdown adapter supports this plan");
    }
}
