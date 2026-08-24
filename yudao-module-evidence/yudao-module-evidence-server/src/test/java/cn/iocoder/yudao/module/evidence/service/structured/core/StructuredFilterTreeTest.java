package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredFilterTreeTest {

    @Mock DomainStructuredDataAdapter adapter;

    @Test
    void resolverSeparatesFilterValueAtChineseDeBoundary() {
        DefaultDomainFieldRegistry registry = new DefaultDomainFieldRegistry();
        registry.register(FieldDefinition.builder().domainCode("PRODUCT").entityType("ITEM")
                .fieldCode("CODE").valueType("STRING").aliases(List.of("编码")).filterable(true).build());

        FilterExpression filter = SimpleStructuredFilterResolver.resolve(
                "编码为P001的名称和地区", "PRODUCT", registry);

        assertThat(filter).isNotNull();
        assertThat(filter.getType()).isEqualTo(FilterExpression.Type.CONDITION);
        assertThat(filter.getFieldCode()).isEqualTo("CODE");
        assertThat(filter.getOperator()).isEqualTo(FilterOperator.EQ);
        assertThat(filter.getValues()).containsExactly("P001");
    }

    @Test
    void evaluatorSupportsAndOrWithoutExternalScripts() {
        StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                .entityId(1L)
                .fields(Map.of("CODE", "P001", "REGION", "Tokyo-East"))
                .build();
        FilterExpression code = FilterExpression.condition("CODE", FilterOperator.EQ, List.of("P001"));
        FilterExpression region = FilterExpression.condition("REGION", FilterOperator.STARTS_WITH, List.of("Tokyo"));
        FilterExpression miss = FilterExpression.condition("REGION", FilterOperator.EQ, List.of("Osaka"));

        assertThat(StructuredFilterEvaluator.matches(row, FilterExpression.and(List.of(code, region)))).isTrue();
        assertThat(StructuredFilterEvaluator.matches(row, FilterExpression.and(List.of(code, miss)))).isFalse();
        assertThat(StructuredFilterEvaluator.matches(row, FilterExpression.or(List.of(miss, code)))).isTrue();
    }

    @Test
    void executorFiltersCompleteRowsBeforeAggregation() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        metrics.register(MetricDefinition.builder().domainCode("PRODUCT").metricCode("ITEM_COUNT")
                .entityType("ITEM").valueType("INTEGER").supportedOperations(Set.of(Operation.COUNT))
                .adapterKey("PRODUCT").build());
        when(adapter.adapterKey()).thenReturn("PRODUCT");
        when(adapter.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of(
                        StructuredQueryResult.Row.builder().entityId(1L).entityKey("1")
                                .fields(Map.of("REGION", "Tokyo")).build(),
                        StructuredQueryResult.Row.builder().entityId(2L).entityKey("2")
                                .fields(Map.of("REGION", "Osaka")).build()))
                .rowCount(2).truncated(false).build());
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metrics, List.of(adapter));
        StructuredQueryPlan plan = StructuredQueryPlan.builder().domainCode("PRODUCT").metricCode("ITEM_COUNT")
                .queryType(QueryType.AGGREGATE).operation(Operation.COUNT)
                .filterExpression(FilterExpression.condition("REGION", FilterOperator.EQ, List.of("Tokyo")))
                .build();

        StructuredQueryResult result = executor.execute(plan);

        assertThat(result.isUnsupported()).isFalse();
        assertThat(result.getValue()).isEqualTo(1D);
        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void filterOnTruncatedSourceFailsClosed() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        metrics.register(MetricDefinition.builder().domainCode("PRODUCT").metricCode("ITEM_COUNT")
                .entityType("ITEM").valueType("INTEGER").supportedOperations(Set.of(Operation.COUNT))
                .adapterKey("PRODUCT").build());
        when(adapter.adapterKey()).thenReturn("PRODUCT");
        when(adapter.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of()).rowCount(0).truncated(true).build());
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metrics, List.of(adapter));
        StructuredQueryPlan plan = StructuredQueryPlan.builder().domainCode("PRODUCT").metricCode("ITEM_COUNT")
                .queryType(QueryType.AGGREGATE).operation(Operation.COUNT)
                .filterExpression(FilterExpression.condition("REGION", FilterOperator.EQ, List.of("Tokyo")))
                .build();

        StructuredQueryResult result = executor.execute(plan);

        assertThat(result.isUnsupported()).isTrue();
        assertThat(result.getUnsupportedReason()).contains("过滤查询").contains("完整数据集");
    }
}
