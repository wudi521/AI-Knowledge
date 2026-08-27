package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁死生产 StructuredPipelineExecutor 的 pushdown 三态语义。
 *
 * <p>这组测试故意让 canonical JVM fallback 本身能够成功，因此 FAILED/SUCCEEDED 场景下
 * verify(never()) 才能证明 Runtime 没有偷偷换路径；只有 UNSUPPORTED 才允许 fallback。</p>
 */
@ExtendWith(MockitoExtension.class)
class StructuredPipelinePushdownRoutingTest {

    private static final String DOMAIN = "PATENT";
    private static final String FIELD = "TITLE";

    @Mock DomainFieldRegistry fieldRegistry;
    @Mock DomainMetricRegistry metricRegistry;
    @Mock DomainStructuredDataAdapter fallbackAdapter;
    @Mock StructuredValueEvaluator values;
    @Mock StructuredPushdownCoordinator coordinator;

    private StructuredPipelineExecutor executor;
    private StructuredValueExpression title;

    @BeforeEach
    void setUp() {
        executor = new StructuredPipelineExecutor(
                fieldRegistry, metricRegistry, List.of(fallbackAdapter), values, coordinator);
        title = StructuredValueExpression.field(FIELD);

        when(values.validate(eq(DOMAIN), any(StructuredValueExpression.class)))
                .thenReturn(new StructuredValueEvaluator.Validation(true, null, "STRING", null));
    }

    @Test
    void succeededPushdownMustReturnAuthoritativeResultWithoutJvmFallback() {
        StructuredPipelineResult authoritative = new StructuredPipelineResult(
                true, null, List.of(), 42D,
                true, false, 9_000, 0,
                Map.of("completeDataset", true, "sourceTruncated", false,
                        "pushdownBackend", "KNOWLEDGE_SQL"));
        when(coordinator.execute(any(StructuredPipelinePlan.class)))
                .thenReturn(StructuredPushdownResult.succeeded(authoritative));

        StructuredPipelineResult result = executor.execute(plan());

        assertThat(result.success()).isTrue();
        assertThat(result.scalarValue()).isEqualTo(42D);
        assertThat(result.completeDataset()).isTrue();
        assertThat(result.metadata()).containsEntry("pushdownExecuted", true)
                .containsEntry("pushdownProofValidated", true)
                .containsEntry("pushdownBackend", "KNOWLEDGE_SQL");
        verify(fallbackAdapter, never()).execute(any());
    }

    @Test
    void failedPushdownMustFailClosedInsteadOfJvmFallback() {
        when(coordinator.execute(any(StructuredPipelinePlan.class)))
                .thenReturn(StructuredPushdownResult.failed("authoritative proof failed"));

        StructuredPipelineResult result = executor.execute(plan());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("structured pushdown failed")
                .contains("authoritative proof failed");
        assertThat(result.metadata()).containsEntry("pushdownFailed", true)
                .containsEntry("completeDataset", false);
        verify(fallbackAdapter, never()).execute(any());
    }

    @Test
    void unsupportedPushdownMustUseCanonicalJvmFallback() {
        when(coordinator.execute(any(StructuredPipelinePlan.class)))
                .thenReturn(StructuredPushdownResult.unsupported("typed aggregate is not registered"));
        when(fallbackAdapter.supports(FIELD)).thenReturn(true);
        when(fallbackAdapter.execute(any(StructuredQueryPlan.class))).thenReturn(source());
        when(values.evaluate(eq(DOMAIN), any(StructuredQueryResult.Row.class), eq(title)))
                .thenAnswer(invocation -> {
                    StructuredQueryResult.Row row = invocation.getArgument(1);
                    String raw = row.getFields().get(FIELD);
                    return StructuredValueEvaluator.ValueEvaluation.success(List.of(raw), raw);
                });

        StructuredPipelineResult result = executor.execute(plan());

        assertThat(result.success()).isTrue();
        assertThat(result.scalarValue()).isEqualTo(1D);
        assertThat(result.completeDataset()).isTrue();
        assertThat(result.metadata()).doesNotContainKey("pushdownFailed");
        verify(fallbackAdapter).execute(any(StructuredQueryPlan.class));
    }

    @Test
    void malformedSucceededPushdownMustFailClosedInsteadOfJvmFallback() {
        StructuredPipelineResult incomplete = new StructuredPipelineResult(
                true, null, List.of(), 42D,
                false, false, 9_000, 0,
                Map.of("completeDataset", false, "sourceTruncated", true));
        when(coordinator.execute(any(StructuredPipelinePlan.class)))
                .thenReturn(StructuredPushdownResult.succeeded(incomplete));

        StructuredPipelineResult result = executor.execute(plan());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("structured pushdown failed")
                .contains("complete dataset coverage");
        assertThat(result.metadata()).containsEntry("pushdownFailed", true);
        verify(fallbackAdapter, never()).execute(any());
    }

    private StructuredPipelinePlan plan() {
        return StructuredPipelinePlan.builder()
                .domainCode(DOMAIN)
                .entityType("PATENT_DOCUMENT")
                .scope(QueryScope.currentKb(9L))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, title, null))
                .build();
    }

    private StructuredQueryResult source() {
        StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                .entityId(1L)
                .entityName("doc-1")
                .fields(Map.of(FIELD, "标题一"))
                .build();
        return StructuredQueryResult.builder()
                .operation(Operation.NONE)
                .rows(List.of(row))
                .rowCount(1)
                .truncated(false)
                .unsupported(false)
                .build();
    }
}
