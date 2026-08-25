package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryIntentValidatorV3Test {

    private QueryIntentValidatorV3 validator;

    @BeforeEach
    void setUp() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        new PatentStructuredPack(metrics, new DefaultDomainEntityRegistry(), fields);
        validator = new QueryIntentValidatorV3(fields, metrics);
    }

    @Test
    void failedPlannerResultCanNeverAppearAsValidatedClarification() {
        QueryIntentV3 failed = QueryIntentV3.builder()
                .domainCode("PATENT")
                .plannerStatus(QueryIntentV3.PlannerStatus.FAILED)
                .plannerSource("FAILED")
                .reasonCode("INVALID_FILTER_OPERATOR")
                .build();

        assertThat(validator.validate(failed))
                .isEqualTo(QueryIntentValidatorV3.Validation.failure("INVALID_FILTER_OPERATOR"));
    }

    @Test
    void rejectsUnknownExternalOperatorBeforeExecutor() {
        QueryIntentV3 intent = executable(
                QueryIntentV3.Selection.builder()
                        .type(QueryIntentV3.SelectionType.STRUCTURED_FILTER)
                        .field("TITLE").operator(null).operatorRaw("GREATER_THAN").values(List.of("磁涌"))
                        .build(),
                QueryIntentV3.Action.builder().type(QueryIntentV3.ActionType.LIST).build());

        assertThat(validator.validate(intent).reasonCode()).isEqualTo("INVALID_FILTER_OPERATOR");
    }

    @Test
    void rejectsOperatorNotAllowedByFieldSchema() {
        QueryIntentV3 intent = executable(
                QueryIntentV3.Selection.builder()
                        .type(QueryIntentV3.SelectionType.STRUCTURED_FILTER)
                        .field("APPLICATION_NO").operator(FilterOperator.CONTAINS)
                        .operatorRaw("CONTAINS").values(List.of("2023"))
                        .build(),
                QueryIntentV3.Action.builder().type(QueryIntentV3.ActionType.LIST).build());

        assertThat(validator.validate(intent).reasonCode()).isEqualTo("FILTER_OPERATOR_NOT_ALLOWED_FOR_FIELD");
    }

    @Test
    void acceptsSchemaCompiledExactProjection() {
        QueryIntentV3 intent = executable(
                QueryIntentV3.Selection.builder()
                        .type(QueryIntentV3.SelectionType.EXACT_ENTITY)
                        .field("APPLICATION_NO").operator(FilterOperator.EQ)
                        .operatorRaw("EQ").values(List.of("202311832214.0"))
                        .build(),
                QueryIntentV3.Action.builder().type(QueryIntentV3.ActionType.PROJECT_FIELDS)
                        .fields(List.of("PUBLICATION_NO")).build());

        assertThat(validator.validate(intent).valid()).isTrue();
    }

    private QueryIntentV3 executable(QueryIntentV3.Selection selection, QueryIntentV3.Action action) {
        return QueryIntentV3.builder().version("3").domainCode("PATENT")
                .plannerStatus(QueryIntentV3.PlannerStatus.EXECUTABLE)
                .selection(selection).actions(List.of(action)).build();
    }
}
