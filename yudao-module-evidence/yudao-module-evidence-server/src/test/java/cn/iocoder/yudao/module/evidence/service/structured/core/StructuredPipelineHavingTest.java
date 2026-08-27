package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for canonical GROUP BY -> aggregate -> HAVING semantics. */
class StructuredPipelineHavingTest {

    private StructuredPipelineExecutor executor;

    @BeforeEach
    void setUp() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        StructuredValueEvaluator values = new StructuredValueEvaluator(fields);

        DomainStructuredDataAdapter adapter = new DomainStructuredDataAdapter() {
            @Override
            public String adapterKey() {
                return "HAVING_TEST";
            }

            @Override
            public boolean supports(String metricCode) {
                return PatentStructuredPack.FIELD_APPLICATION_NO.equals(metricCode)
                        || PatentStructuredPack.FIELD_INVENTOR.equals(metricCode);
            }

            @Override
            public StructuredQueryResult execute(StructuredQueryPlan plan) {
                return StructuredQueryResult.builder()
                        .rows(List.of(
                                row(1L, "P-1", "张三"),
                                row(2L, "P-2", "李四、王五"),
                                row(3L, "P-3", "赵六、钱七、孙八")
                        ))
                        .rowCount(3)
                        .truncated(false)
                        .build();
            }
        };
        executor = new StructuredPipelineExecutor(fields, metrics, List.of(adapter), values);
    }

    @Test
    void groupedAggregateAppliesHavingBeforeSortAndLimit() {
        StructuredValueExpression applicationNo = new StructuredValueExpression(
                PatentStructuredPack.FIELD_APPLICATION_NO, false, List.of());
        StructuredValueExpression inventors = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .groupBy(List.of(applicationNo))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, inventors, null))
                .having(new StructuredHavingSpec(FilterOperator.GT, List.of(1D)))
                .orderBy(List.of(new StructuredOrderSpec(null, null, true, SortDirection.DESC)))
                .limit(1)
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).groupKey()).isEqualTo("P-3");
        assertThat(result.rows().get(0).value()).isEqualTo(3D);
        assertThat(result.metadata()).containsEntry("preHavingGroupCount", 3)
                .containsEntry("fullGroupCount", 2)
                .containsEntry("havingApplied", true)
                .containsEntry("limited", true);
    }

    @Test
    void havingCanProduceAuthoritativeEmptyGroupedResult() {
        StructuredValueExpression applicationNo = new StructuredValueExpression(
                PatentStructuredPack.FIELD_APPLICATION_NO, false, List.of());
        StructuredValueExpression inventors = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .groupBy(List.of(applicationNo))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, inventors, null))
                .having(new StructuredHavingSpec(FilterOperator.GT, List.of(9D)))
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.rows()).isEmpty();
        assertThat(result.authoritativeEmpty()).isTrue();
        assertThat(result.completeDataset()).isTrue();
        assertThat(result.metadata()).containsEntry("preHavingGroupCount", 3)
                .containsEntry("fullGroupCount", 0)
                .containsEntry("havingApplied", true);
    }

    private StructuredQueryResult.Row row(Long id, String applicationNo, String inventors) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put(PatentStructuredPack.FIELD_APPLICATION_NO, applicationNo);
        fields.put(PatentStructuredPack.FIELD_INVENTOR, inventors);
        return StructuredQueryResult.Row.builder()
                .entityId(id)
                .entityKey("APP:" + applicationNo)
                .entityName(applicationNo)
                .fields(fields)
                .build();
    }
}
