package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicQueryPlannerV3Test {

    private DeterministicQueryPlannerV3 planner;

    @BeforeEach
    void setUp() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(new DefaultDomainMetricRegistry(), new DefaultDomainEntityRegistry(), fields);
        planner = new DeterministicQueryPlannerV3(fields);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "申请号 202311832214.0 的公布号是什么？|APPLICATION_NO|202311832214.0|PUBLICATION_NO",
            "查询202311832214.0对应的公开号|APPLICATION_NO|202311832214.0|PUBLICATION_NO",
            "公布号是什么，申请号是202311832214.0|APPLICATION_NO|202311832214.0|PUBLICATION_NO",
            "公布号 CN 122619519 A 的申请号|PUBLICATION_NO|CN 122619519 A|APPLICATION_NO",
            "CN122619519A对应的申请编号|PUBLICATION_NO|CN122619519A|APPLICATION_NO"
    }, delimiter = '|')
    void compilesExactIdentifierProjectionWithoutLlm(String query, String sourceField,
                                                      String value, String projection) {
        QueryIntentV3 intent = planner.tryPlan(query, PatentStructuredPack.DOMAIN_CODE).orElseThrow();

        assertThat(intent.getPlannerSource()).isEqualTo("DETERMINISTIC_SCHEMA");
        assertThat(intent.getPlannerStatus()).isEqualTo(QueryIntentV3.PlannerStatus.EXECUTABLE);
        assertThat(intent.getSelection().getType()).isEqualTo(QueryIntentV3.SelectionType.EXACT_ENTITY);
        assertThat(intent.getSelection().getField()).isEqualTo(sourceField);
        assertThat(intent.getSelection().getOperator()).isEqualTo(FilterOperator.EQ);
        assertThat(intent.getSelection().getValues()).containsExactly(value);
        assertThat(intent.getActions()).singleElement().satisfies(action -> {
            assertThat(action.getType()).isEqualTo(QueryIntentV3.ActionType.PROJECT_FIELDS);
            assertThat(action.getFields()).containsExactly(projection);
        });
    }

    @Test
    void supportsMultipleProjectionFieldsFromSchemaAliases() {
        QueryIntentV3 intent = planner.tryPlan(
                "申请号202311832214.0的公布号、申请人和发明人分别是什么",
                PatentStructuredPack.DOMAIN_CODE).orElseThrow();

        assertThat(intent.getActions().get(0).getFields())
                .containsExactly("PUBLICATION_NO", "APPLICANT", "INVENTOR");
    }

    @Test
    void doesNotMisrouteComplexSemanticRelationOrMultipleIdentifiers() {
        assertThat(planner.tryPlan("与申请号202311832214.0类似的专利公布号", "PATENT")).isEmpty();
        assertThat(planner.tryPlan(
                "比较申请号202311832214.0和202311042981.1的公布号", "PATENT")).isEmpty();
    }

    @Test
    void everyConfiguredIdentifierPatternCanBeCompiled() {
        assertThat(planner.tryPlan("申请号202311832214.0的公布号", "PATENT")).isPresent();
        assertThat(planner.tryPlan("公布号CN 122619519 A的申请号", "PATENT")).isPresent();
    }
}
