package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Query IR 的表达力回归：新语义由原语组合，不增加业务 intent/task。 */
class StructuredQueryIrCompositionTest {

    @Test
    void canComposeGroupedAverageOfTransformedFieldAndTopNWithoutSemanticIntent() {
        StructuredValueExpression applicant = StructuredValueExpression.field("APPLICANT");
        StructuredValueExpression titleLength = new StructuredValueExpression(
                "TITLE", false, List.of(StructuredValueTransform.LENGTH));

        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode("PATENT")
                .entityType("PATENT_DOCUMENT")
                .groupBy(List.of(applicant))
                .aggregate(new StructuredAggregateSpec(Operation.AVG, titleLength, null))
                .orderBy(List.of(new StructuredOrderSpec(null, null, true, SortDirection.DESC)))
                .limit(5)
                .build();

        assertThat(StructuredPipelinePlan.IR_VERSION).isEqualTo("QUERY_IR_V1");
        assertThat(plan.getGroupBy()).containsExactly(applicant);
        assertThat(plan.getAggregate().operation()).isEqualTo(Operation.AVG);
        assertThat(plan.getAggregate().value().transforms()).containsExactly(StructuredValueTransform.LENGTH);
        assertThat(plan.getOrderBy()).singleElement().satisfies(order -> {
            assertThat(order.aggregateValue()).isTrue();
            assertThat(order.direction()).isEqualTo(SortDirection.DESC);
        });
        assertThat(plan.getLimit()).isEqualTo(5);
    }
}
