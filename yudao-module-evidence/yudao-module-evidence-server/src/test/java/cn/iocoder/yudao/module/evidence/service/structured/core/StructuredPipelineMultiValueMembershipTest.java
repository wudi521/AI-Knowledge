package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归真实生产数据形态：多值字段使用 U+3000 全角空格分隔时，
 * membership filter 必须与 explode/VALUE_COUNT 使用同一 Field Schema 分隔契约。
 */
class StructuredPipelineMultiValueMembershipTest {

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
                return "TEST";
            }

            @Override
            public boolean supports(String metricCode) {
                return PatentStructuredPack.FIELD_INVENTOR.equals(metricCode);
            }

            @Override
            public StructuredQueryResult execute(StructuredQueryPlan plan) {
                LinkedHashMap<String, String> rowFields = new LinkedHashMap<>();
                rowFields.put(PatentStructuredPack.FIELD_INVENTOR,
                        "郝海涛　吴恒莉　贾少微　何昕　");
                StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                        .entityId(74L)
                        .entityKey("APP:2023118322140")
                        .entityName("一种体外经颅式治疗仪")
                        .fields(rowFields)
                        .build();
                return StructuredQueryResult.builder()
                        .rows(List.of(row))
                        .rowCount(1)
                        .truncated(false)
                        .build();
            }
        };
        executor = new StructuredPipelineExecutor(fields, metrics, List.of(adapter), values);
    }

    @Test
    void filteredEntityCountMustFindMiddleElementSeparatedByIdeographicSpaces() {
        StructuredValueExpression inventor = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());
        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .filter(StructuredPredicateNode.condition(inventor, FilterOperator.EQ, List.of("贾少微")))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null, null))
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.scalarValue()).isEqualTo(1D);
        assertThat(result.completeDataset()).isTrue();
        assertThat(result.metadata()).containsEntry("outputComplete", true);
        assertThat(result.authoritativeEmpty()).isFalse();
        assertThat(result.sourceEntityCount()).isEqualTo(1);
    }

    @Test
    void filteredEntityCountMayBeAuthoritativeZeroOnlyForARealNonMember() {
        StructuredValueExpression inventor = new StructuredValueExpression(
                PatentStructuredPack.FIELD_INVENTOR, true, List.of());
        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .filter(StructuredPredicateNode.condition(inventor, FilterOperator.EQ, List.of("不存在的人")))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null, null))
                .build();

        StructuredPipelineResult result = executor.execute(plan);

        assertThat(result.success()).isTrue();
        assertThat(result.scalarValue()).isEqualTo(0D);
        assertThat(result.completeDataset()).isTrue();
        assertThat(result.metadata()).containsEntry("outputComplete", true);
        assertThat(result.authoritativeEmpty()).isTrue();
    }
}
