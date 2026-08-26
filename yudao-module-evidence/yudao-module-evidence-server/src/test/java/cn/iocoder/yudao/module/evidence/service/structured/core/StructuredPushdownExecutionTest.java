package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredPushdownExecutionTest {

    @Test
    void successfulPushdownBypassesRowMaterialization() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainStructuredDataAdapter rowAdapter = mock(DomainStructuredDataAdapter.class);
        StructuredValueEvaluator values = mock(StructuredValueEvaluator.class);
        MetricDefinition metric = metric();
        when(metrics.lookup("TEST", "M")).thenReturn(Optional.of(metric));

        StructuredPushdownAdapter pushdown = new StructuredPushdownAdapter() {
            @Override public String domainCode() { return "TEST"; }
            @Override public boolean supports(StructuredPipelinePlan plan) { return true; }
            @Override public StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
                return StructuredPushdownResult.succeeded(new StructuredPipelineResult(
                        true, null, List.of(), 42D, true, false, 42, 0,
                        Map.of("pushdownExecuted", true)));
            }
        };
        ElementBindingStructuredPipelineExecutor executor = new ElementBindingStructuredPipelineExecutor(
                fields, metrics, List.of(rowAdapter), values,
                new StructuredPushdownCoordinator(List.of(pushdown)));

        StructuredPipelineResult result = executor.execute(plan());

        assertTrue(result.success());
        assertEquals(42D, result.scalarValue());
        assertEquals(true, result.metadata().get("pushdownExecuted"));
        assertEquals("SCALAR", result.metadata().get("resultShape"));
        verify(rowAdapter, never()).execute(any());
    }

    @Test
    void failedPushdownNeverFallsBackToRows() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainStructuredDataAdapter rowAdapter = mock(DomainStructuredDataAdapter.class);
        StructuredValueEvaluator values = mock(StructuredValueEvaluator.class);
        when(metrics.lookup("TEST", "M")).thenReturn(Optional.of(metric()));

        StructuredPushdownAdapter pushdown = new StructuredPushdownAdapter() {
            @Override public String domainCode() { return "TEST"; }
            @Override public boolean supports(StructuredPipelinePlan plan) { return true; }
            @Override public StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
                return StructuredPushdownResult.failed("database unavailable");
            }
        };
        ElementBindingStructuredPipelineExecutor executor = new ElementBindingStructuredPipelineExecutor(
                fields, metrics, List.of(rowAdapter), values,
                new StructuredPushdownCoordinator(List.of(pushdown)));

        StructuredPipelineResult result = executor.execute(plan());

        assertFalse(result.success());
        assertEquals(true, result.metadata().get("pushdownFailed"));
        verify(rowAdapter, never()).execute(any());
    }

    @Test
    void unsupportedPushdownFallsBackToExistingRowExecutor() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainStructuredDataAdapter rowAdapter = mock(DomainStructuredDataAdapter.class);
        StructuredValueEvaluator values = mock(StructuredValueEvaluator.class);
        MetricDefinition metric = metric();
        when(metrics.lookup("TEST", "M")).thenReturn(Optional.of(metric));
        when(rowAdapter.supports("M")).thenReturn(true);
        when(rowAdapter.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of(
                        StructuredQueryResult.Row.builder().entityId(1L).value(1D).build(),
                        StructuredQueryResult.Row.builder().entityId(2L).value(1D).build()))
                .truncated(false)
                .build());

        ElementBindingStructuredPipelineExecutor executor = new ElementBindingStructuredPipelineExecutor(
                fields, metrics, List.of(rowAdapter), values,
                new StructuredPushdownCoordinator(List.of()));

        StructuredPipelineResult result = executor.execute(plan());

        assertTrue(result.success());
        assertEquals(2D, result.scalarValue());
        verify(rowAdapter).execute(any());
    }

    private StructuredPipelinePlan plan() {
        return StructuredPipelinePlan.builder()
                .domainCode("TEST")
                .entityType("E")
                .scope(QueryScope.currentKb(1L))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null, "M"))
                .build();
    }

    private MetricDefinition metric() {
        return MetricDefinition.builder()
                .domainCode("TEST")
                .metricCode("M")
                .entityType("E")
                .dataGrain(DataGrain.LOGICAL_ENTITY)
                .valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT))
                .adapterKey("TEST")
                .build();
    }
}
