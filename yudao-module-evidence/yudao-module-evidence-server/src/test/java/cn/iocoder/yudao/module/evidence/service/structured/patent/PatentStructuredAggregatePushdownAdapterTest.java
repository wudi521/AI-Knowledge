package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAggregateSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredAggregateApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatentStructuredAggregatePushdownAdapterTest {

    @Test
    void patentCountUsesAuthoritativeAggregateRpcAndPublishesLogicalGrain() {
        KnowledgeStructuredAggregateApi api = mock(KnowledgeStructuredAggregateApi.class);
        StructuredAggregateRespDTO response = new StructuredAggregateRespDTO();
        response.setMetricCode(PatentStructuredPack.METRIC_PATENT_COUNT);
        response.setValue(9L);
        response.setSourceRowCount(9L);
        response.setCompleteDataset(true);
        when(api.aggregate(any())).thenReturn(CommonResult.success(response));

        PatentStructuredAggregatePushdownAdapter adapter = new PatentStructuredAggregatePushdownAdapter(api);
        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null,
                        PatentStructuredPack.METRIC_PATENT_COUNT))
                .build();

        StructuredPushdownResult result = adapter.executePushdown(plan);

        assertEquals(StructuredPushdownResult.Status.SUCCEEDED, result.status());
        assertEquals(9D, result.result().scalarValue());
        assertEquals("LOGICAL_ENTITY", result.result().metadata().get("dataGrain"));
        assertTrue(Boolean.TRUE.equals(result.result().metadata().get("pushdownExecuted")));
        ArgumentCaptor<cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO> captor =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO.class);
        verify(api).aggregate(captor.capture());
        assertEquals(6L, captor.getValue().getKbId());
        assertEquals(PatentStructuredPack.METRIC_PATENT_COUNT, captor.getValue().getMetricCode());
    }

    @Test
    void filteredCountDoesNotPretendPushdownSupport() {
        KnowledgeStructuredAggregateApi api = mock(KnowledgeStructuredAggregateApi.class);
        PatentStructuredAggregatePushdownAdapter adapter = new PatentStructuredAggregatePushdownAdapter(api);
        StructuredPipelinePlan plan = StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .filter(cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPredicateNode.condition(
                        new cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression(
                                PatentStructuredPack.FIELD_TITLE, false, java.util.List.of()),
                        cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.CONTAINS,
                        java.util.List.of("磁涌")))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null,
                        PatentStructuredPack.METRIC_PATENT_COUNT))
                .build();

        assertTrue(!adapter.supports(plan));
    }
}
