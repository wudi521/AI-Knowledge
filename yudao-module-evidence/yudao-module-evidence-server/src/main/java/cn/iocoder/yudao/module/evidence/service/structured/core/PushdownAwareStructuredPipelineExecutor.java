package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产 Structured Pipeline 入口：优先尝试权威存储层整计划下推，只有明确 UNSUPPORTED 才回退行级完整扫描。
 *
 * <p>这个类故意不包含 PATENT/CONTRACT 等领域分支。领域能力通过 {@link StructuredPushdownAdapter}
 * 插件注册，Core 只负责三态协议：</p>
 * <ul>
 *   <li>SUCCEEDED：直接使用权威后端结果；</li>
 *   <li>UNSUPPORTED：回退 {@link StructuredPipelineExecutor} 的通用完整语义；</li>
 *   <li>FAILED：fail-closed，禁止后端故障后偷偷换成另一条可能不同语义的执行路径。</li>
 * </ul>
 */
@Primary
@Component
public class PushdownAwareStructuredPipelineExecutor extends StructuredPipelineExecutor {

    private final StructuredPushdownCoordinator pushdownCoordinator;

    public PushdownAwareStructuredPipelineExecutor(DomainFieldRegistry fieldRegistry,
                                                    DomainMetricRegistry metricRegistry,
                                                    List<DomainStructuredDataAdapter> adapters,
                                                    StructuredValueEvaluator values,
                                                    StructuredPushdownCoordinator pushdownCoordinator) {
        super(fieldRegistry, metricRegistry, adapters, values);
        this.pushdownCoordinator = pushdownCoordinator;
    }

    @Override
    public StructuredPipelineResult execute(StructuredPipelinePlan plan) {
        StructuredPushdownResult pushdown = pushdownCoordinator == null
                ? StructuredPushdownResult.unsupported("pushdown coordinator unavailable")
                : pushdownCoordinator.execute(plan);
        if (pushdown == null) {
            return pushdownFailure("pushdown coordinator returned null", null);
        }

        return switch (pushdown.status()) {
            case UNSUPPORTED -> super.execute(plan);
            case FAILED -> pushdownFailure(pushdown.reason(), null);
            case SUCCEEDED -> validateSucceededPushdown(pushdown.result());
        };
    }

    private StructuredPipelineResult validateSucceededPushdown(StructuredPipelineResult result) {
        if (result == null) return pushdownFailure("pushdown succeeded without result", null);
        if (!result.success()) {
            return pushdownFailure("pushdown returned unsuccessful pipeline result: " + safe(result.message()),
                    result.metadata());
        }
        if (!result.completeDataset()) {
            return pushdownFailure("pushdown result cannot prove complete dataset coverage", result.metadata());
        }
        if (result.missingValueCount() > 0) {
            return pushdownFailure("pushdown result contains missing required values", result.metadata());
        }

        Map<String, Object> proof = new LinkedHashMap<>();
        if (result.metadata() != null) proof.putAll(result.metadata());
        proof.put("pushdownExecuted", true);
        proof.put("completeDataset", true);
        proof.putIfAbsent("sourceTruncated", false);
        proof.put("pushdownProofValidated", true);
        return new StructuredPipelineResult(true, result.message(), result.rows(), result.scalarValue(),
                true, result.authoritativeEmpty(), result.sourceEntityCount(), 0, proof);
    }

    private StructuredPipelineResult pushdownFailure(String reason, Map<String, Object> sourceMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sourceMetadata != null) metadata.putAll(sourceMetadata);
        metadata.put("pushdownExecuted", true);
        metadata.put("pushdownFailed", true);
        metadata.put("completeDataset", false);
        metadata.put("sourceTruncated", false);
        return StructuredPipelineResult.failure("structured pushdown failed: " + safe(reason), metadata);
    }

    private String safe(String reason) {
        return reason == null || reason.isBlank() ? "unknown reason" : reason;
    }
}
