package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.agent.AgentStopReason;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimilarFieldValuesCapabilityTest {

    @Test
    void collectionTitleSimilarityReturnsPairsFromCompleteDataset() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        StructuredQueryExecutor executor = mock(StructuredQueryExecutor.class);
        FieldDefinition title = titleField();
        when(fields.all("PATENT")).thenReturn(List.of(title));
        when(executor.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of(
                        row(1L, "一种无人机垂直起降装置"),
                        row(2L, "一种无人机垂直起降系统"),
                        row(3L, "磁性材料制备方法")))
                .rowCount(3)
                .truncated(false)
                .build());

        SimilarFieldValuesCapability capability = new SimilarFieldValuesCapability(fields, metrics, executor);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-similar"),
                Map.of("field", "专利名称", "threshold", 0.50D, "topN", 10));

        assertTrue(result.success());
        SimilarFieldValuesCapability.Output output = (SimilarFieldValuesCapability.Output) result.data();
        assertEquals("TITLE", output.fieldCode());
        assertEquals(3, output.entityCount());
        assertFalse(output.pairs().isEmpty());
        assertEquals(1L, output.pairs().get(0).leftEntityId());
        assertEquals(2L, output.pairs().get(0).rightEntityId());
        assertTrue(output.deterministicAnswer().contains("发现"));
        assertEquals(Boolean.TRUE, result.metadata().get("completeDataset"));
    }

    @Test
    void missingAnyTitleMustFailClosedForCollectionWideConclusion() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        StructuredQueryExecutor executor = mock(StructuredQueryExecutor.class);
        when(fields.all("PATENT")).thenReturn(List.of(titleField()));
        when(executor.execute(any())).thenReturn(StructuredQueryResult.builder()
                .rows(List.of(row(1L, "完整标题"), row(2L, null)))
                .rowCount(2)
                .truncated(false)
                .build());

        SimilarFieldValuesCapability capability = new SimilarFieldValuesCapability(fields, metrics, executor);
        CapabilityResult result = capability.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-similar-missing"),
                Map.of("field", "TITLE"));

        assertFalse(result.success());
        assertEquals(AgentStopReason.NO_RELIABLE_EVIDENCE, result.stopReason());
        assertTrue(result.message().contains("field data is incomplete"));
    }

    private FieldDefinition titleField() {
        return FieldDefinition.builder()
                .fieldCode("TITLE")
                .domainCode("PATENT")
                .entityType("PATENT_DOCUMENT")
                .valueType("STRING")
                .aliases(List.of("标题", "专利名称", "发明名称"))
                .build();
    }

    private StructuredQueryResult.Row row(Long id, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TITLE", title);
        return StructuredQueryResult.Row.builder()
                .entityId(id)
                .entityName(title == null ? "对象" + id : title)
                .fields(fields)
                .build();
    }
}
