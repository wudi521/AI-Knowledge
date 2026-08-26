package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredSourceRecordGroupingTest {

    @Test
    void sourceRecordMetricMustDriveGroupingOnPhysicalRows() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        AtomicReference<String> requestedMetric = new AtomicReference<>();

        DomainStructuredDataAdapter adapter = new DomainStructuredDataAdapter() {
            @Override public String adapterKey() { return "TEST"; }
            @Override public boolean supports(String code) { return true; }

            @Override
            public StructuredQueryResult execute(StructuredQueryPlan plan) {
                requestedMetric.set(plan.getMetricCode());
                return StructuredQueryResult.builder()
                        .rows(List.of(
                                row(1L, "A-1", "重复文档", 1D),
                                row(2L, "A-1", "重复文档", 1D),
                                row(3L, "A-2", "独立文档", 1D)))
                        .rowCount(3)
                        .truncated(false)
                        .build();
            }

            private StructuredQueryResult.Row row(Long id, String applicationNo, String title, Double value) {
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                values.put(PatentStructuredPack.FIELD_APPLICATION_NO, applicationNo);
                values.put(PatentStructuredPack.FIELD_TITLE, title);
                return StructuredQueryResult.Row.builder()
                        .entityId(id)
                        .entityKey("DOC:" + id)
                        .entityName(title)
                        .value(value)
                        .fields(values)
                        .build();
            }
        };

        StructuredValueEvaluator values = new StructuredValueEvaluator(fields);
        ElementBindingStructuredPipelineExecutor executor = new ElementBindingStructuredPipelineExecutor(
                fields, metrics, List.of(adapter), values);
        StructuredValueExpression applicationNo = new StructuredValueExpression(
                PatentStructuredPack.FIELD_APPLICATION_NO, false, List.of());
        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .groupBy(List.of(applicationNo))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null,
                        PatentStructuredPack.METRIC_DOCUMENT_COUNT))
                .orderBy(List.of(new StructuredOrderSpec(null, null, true, SortDirection.DESC)))
                .limit(20)
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(requestedMetric.get()).isEqualTo(PatentStructuredPack.METRIC_DOCUMENT_COUNT);
        assertThat(result.metadata()).containsEntry("dataGrain", DataGrain.SOURCE_RECORD.name());
        assertThat(result.rows()).extracting(StructuredPipelineResult.Row::groupKey)
                .containsExactly("A-1", "A-2");
        assertThat(result.rows()).extracting(StructuredPipelineResult.Row::value)
                .containsExactly(2D, 1D);
    }
}
