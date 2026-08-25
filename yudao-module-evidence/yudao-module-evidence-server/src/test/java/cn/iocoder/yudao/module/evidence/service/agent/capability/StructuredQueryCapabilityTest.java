package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAnswerRenderer;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredQueryCapabilityTest {

    @Test
    void exactIdentifierFilterMayProjectAnotherRegisteredFieldAndReturnTrustedEntity() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainEntityRegistry entities = mock(DomainEntityRegistry.class);
        StructuredQueryExecutor executor = mock(StructuredQueryExecutor.class);
        StructuredAnswerRenderer renderer = mock(StructuredAnswerRenderer.class);

        FieldDefinition applicationNo = field("APPLICATION_NO", List.of("申请号"), Set.of(FilterOperator.EQ), true);
        FieldDefinition publicationNo = field("PUBLICATION_NO", List.of("公布号"), Set.of(FilterOperator.EQ), true);
        when(fields.byCode("PATENT", "APPLICATION_NO")).thenReturn(Optional.of(applicationNo));
        when(fields.byCode("PATENT", "PUBLICATION_NO")).thenReturn(Optional.of(publicationNo));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("APPLICATION_NO", "202311832214.0");
        values.put("PUBLICATION_NO", "CN123456789A");
        StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                .entityId(74L)
                .entityName("测试专利")
                .fields(values)
                .build();
        when(executor.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of(row)).rowCount(1).truncated(false).build());

        StructuredQueryCapability capability = new StructuredQueryCapability(fields, metrics, entities, executor, renderer);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-structured"),
                Map.of(
                        "task", "PROJECT",
                        "field", "APPLICATION_NO",
                        "operator", "EQ",
                        "values", List.of("202311832214.0"),
                        "projections", List.of("PUBLICATION_NO")
                ));

        assertTrue(result.success());
        StructuredQueryCapability.Output output = (StructuredQueryCapability.Output) result.data();
        assertEquals(List.of(74L), output.verifiedEntityIds());
        assertTrue(output.deterministicAnswer().contains("CN123456789A"));
        assertTrue(output.summary().contains("entityIds=[74]"));
        assertEquals(Boolean.TRUE, result.metadata().get("completeDataset"));
    }

    @Test
    void machineArgumentContractRejectsWrongShapesBeforeExecution() {
        StructuredQueryCapability capability = new StructuredQueryCapability(
                mock(DomainFieldRegistry.class), mock(DomainMetricRegistry.class), mock(DomainEntityRegistry.class),
                mock(StructuredQueryExecutor.class), mock(StructuredAnswerRenderer.class));
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "arg-contract");

        CapabilityArgumentValidation badLimit = capability.validateArguments(context, Map.of("limit", 3.5D));
        CapabilityArgumentValidation badDistinct = capability.validateArguments(context, Map.of("distinct", "true"));
        CapabilityArgumentValidation badFilter = capability.validateArguments(context, Map.of("filter", "FILING_DATE >= 2024"));
        CapabilityArgumentValidation good = capability.validateArguments(context, Map.of(
                "select", List.of("TITLE", Map.of("field", "FILING_DATE", "transforms", List.of("YEAR"))),
                "filter", Map.of("field", "FILING_DATE", "operator", "GTE", "values", List.of("2024-01-01")),
                "distinct", true,
                "limit", 3
        ));

        assertFalse(badLimit.valid());
        assertTrue(badLimit.message().contains("integer"));
        assertFalse(badDistinct.valid());
        assertTrue(badDistinct.message().contains("boolean"));
        assertFalse(badFilter.valid());
        assertTrue(badFilter.message().contains("object"));
        assertTrue(good.valid());
    }

    private FieldDefinition field(String code, List<String> aliases, Set<FilterOperator> operators,
                                  boolean exactIdentifier) {
        return FieldDefinition.builder()
                .fieldCode(code)
                .domainCode("PATENT")
                .entityType("PATENT_DOCUMENT")
                .valueType("STRING")
                .aliases(aliases)
                .allowedOperators(operators)
                .filterable(true)
                .exactIdentifier(exactIdentifier)
                .build();
    }
}
