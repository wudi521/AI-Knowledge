package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElementBindingStructuredPipelineExecutorTest {

    private ElementBindingStructuredPipelineExecutor executor;

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
                return "TEST";
            }

            @Override
            public boolean supports(String metricCode) {
                return PatentStructuredPack.FIELD_INVENTOR.equals(metricCode)
                        || PatentStructuredPack.FIELD_TITLE.equals(metricCode);
            }

            @Override
            public StructuredQueryResult execute(StructuredQueryPlan plan) {
                LinkedHashMap<String, String> rowFields = new LinkedHashMap<>();
                rowFields.put(PatentStructuredPack.FIELD_INVENTOR,
                        "孟祥军　朱姜涛　李邵波　张明杰　");
                rowFields.put(PatentStructuredPack.FIELD_TITLE, "测试设备");
                StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                        .entityId(70L)
                        .entityKey("APP:TEST")
                        .entityName("测试专利 · 孟祥军　朱姜涛　李邵波　张明杰")
                        .fields(rowFields)
                        .build();
                return StructuredQueryResult.builder()
                        .rows(List.of(row))
                        .rowCount(1)
                        .truncated(false)
                        .build();
            }
        };

        executor = new ElementBindingStructuredPipelineExecutor(fields, metrics, List.of(adapter), values);
    }

    @Test
    void transformedFilterMustReturnOnlyMatchingRawMultiValueElement() {
        StructuredValueExpression inventor = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());
        StructuredValueExpression surname = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true,
                List.of(StructuredValueTransform.PERSON_SURNAME));

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(inventor, surname))
                .filter(StructuredPredicateNode.condition(
                        surname, FilterOperator.EQ, List.of("李")))
                .limit(20)
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(1);
        StructuredPipelineResult.Row row = result.rows().get(0);
        assertThat(row.fields()).containsEntry("INVENTOR|EXPLODE", "李邵波");
        assertThat(row.fields()).containsEntry("INVENTOR|EXPLODE|PERSON_SURNAME", "李");
        assertThat(result.metadata()).containsEntry("elementBindingApplied", true);
        assertThat(result.metadata()).containsEntry("elementBindingRemoved", 15);
        assertThat(result.metadata()).containsEntry("outputComplete", true);
    }

    @Test
    void exactDerivedProjectionMustNotKeepSiblingElementsFromSameEntity() {
        StructuredValueExpression surname = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true,
                List.of(StructuredValueTransform.PERSON_SURNAME));

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(surname))
                .filter(StructuredPredicateNode.condition(
                        surname, FilterOperator.EQ, List.of("李")))
                .limit(20)
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).fields())
                .containsExactlyEntriesOf(java.util.Map.of("INVENTOR|EXPLODE|PERSON_SURNAME", "李"));
    }

    @Test
    void unrelatedOrBranchMustNotOverBindElements() {
        // OR 中只要有一个分支来自其它字段，就不能把某个元素级条件强行套到所有命中实体上。
        StructuredValueExpression inventor = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());
        StructuredValueExpression surname = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true,
                List.of(StructuredValueTransform.PERSON_SURNAME));
        StructuredValueExpression title = new StructuredValueExpression(
                PatentStructuredPack.FIELD_TITLE, false, List.of());

        StructuredPredicateNode filter = StructuredPredicateNode.or(List.of(
                StructuredPredicateNode.condition(surname, FilterOperator.EQ, List.of("不存在")),
                StructuredPredicateNode.condition(title, FilterOperator.CONTAINS, List.of("设备"))));

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(inventor))
                .filter(filter)
                .limit(20)
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.rows()).extracting(r -> r.fields().get("INVENTOR|EXPLODE"))
                .containsExactly("孟祥军", "朱姜涛", "李邵波", "张明杰");
    }
}
