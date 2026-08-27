package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAggregateSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredAggregateApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredOrderApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredPageApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        StructuredPushdownResult result = adapter.executePushdown(countPlan());

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
    void titleLengthMaxUsesAuthoritativeDescendingOrderProof() {
        KnowledgeStructuredAggregateApi aggregateApi = mock(KnowledgeStructuredAggregateApi.class);
        KnowledgeStructuredOrderApi orderApi = mock(KnowledgeStructuredOrderApi.class);
        KnowledgeStructuredPageApi pageApi = mock(KnowledgeStructuredPageApi.class);
        when(orderApi.order(any())).thenReturn(CommonResult.success(orderProof(99L, 7L, 0L, 0L)));
        when(pageApi.page(any())).thenReturn(CommonResult.success(page(99L, "最长标题ABC")));

        PatentStructuredAggregatePushdownAdapter adapter =
                new PatentStructuredAggregatePushdownAdapter(aggregateApi, orderApi, pageApi);
        StructuredPushdownResult result = adapter.executePushdown(titleLengthPlan(Operation.MAX));

        assertEquals(StructuredPushdownResult.Status.SUCCEEDED, result.status());
        assertEquals((double) "最长标题ABC".codePointCount(0, "最长标题ABC".length()), result.result().scalarValue());
        assertEquals("AGGREGATE_EXTREMUM", result.result().metadata().get("pushdownOperation"));
        assertEquals(99L, result.result().metadata().get("winnerEntityId"));

        ArgumentCaptor<StructuredOrderReqDTO> captor = ArgumentCaptor.forClass(StructuredOrderReqDTO.class);
        verify(orderApi).order(captor.capture());
        assertEquals("DESC", captor.getValue().getDirection());
        assertEquals("TITLE", captor.getValue().getFieldCode());
        assertEquals("LENGTH", captor.getValue().getTransformCode());
        verify(aggregateApi, never()).aggregate(any());
    }

    @Test
    void titleLengthMinUsesAuthoritativeAscendingOrderProof() {
        KnowledgeStructuredAggregateApi aggregateApi = mock(KnowledgeStructuredAggregateApi.class);
        KnowledgeStructuredOrderApi orderApi = mock(KnowledgeStructuredOrderApi.class);
        KnowledgeStructuredPageApi pageApi = mock(KnowledgeStructuredPageApi.class);
        when(orderApi.order(any())).thenReturn(CommonResult.success(orderProof(11L, 7L, 0L, 0L)));
        when(pageApi.page(any())).thenReturn(CommonResult.success(page(11L, "短")));

        PatentStructuredAggregatePushdownAdapter adapter =
                new PatentStructuredAggregatePushdownAdapter(aggregateApi, orderApi, pageApi);
        StructuredPushdownResult result = adapter.executePushdown(titleLengthPlan(Operation.MIN));

        assertEquals(StructuredPushdownResult.Status.SUCCEEDED, result.status());
        assertEquals(1D, result.result().scalarValue());
        ArgumentCaptor<StructuredOrderReqDTO> captor = ArgumentCaptor.forClass(StructuredOrderReqDTO.class);
        verify(orderApi).order(captor.capture());
        assertEquals("ASC", captor.getValue().getDirection());
    }

    @Test
    void titleLengthExtremumWithMissingTitleMustFailClosedBeforeMaterialization() {
        KnowledgeStructuredAggregateApi aggregateApi = mock(KnowledgeStructuredAggregateApi.class);
        KnowledgeStructuredOrderApi orderApi = mock(KnowledgeStructuredOrderApi.class);
        KnowledgeStructuredPageApi pageApi = mock(KnowledgeStructuredPageApi.class);
        when(orderApi.order(any())).thenReturn(CommonResult.success(orderProof(null, 7L, 1L, 0L)));

        PatentStructuredAggregatePushdownAdapter adapter =
                new PatentStructuredAggregatePushdownAdapter(aggregateApi, orderApi, pageApi);
        StructuredPushdownResult result = adapter.executePushdown(titleLengthPlan(Operation.MAX));

        assertEquals(StructuredPushdownResult.Status.FAILED, result.status());
        assertTrue(result.reason().contains("missing TITLE"));
        verify(pageApi, never()).page(any());
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
                        new StructuredValueExpression(PatentStructuredPack.FIELD_TITLE, false, List.of()),
                        cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator.CONTAINS,
                        List.of("磁涌")))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null,
                        PatentStructuredPack.METRIC_PATENT_COUNT))
                .build();

        assertTrue(!adapter.supports(plan));
    }

    @Test
    void unrelatedAverageDoesNotPretendPushdownSupport() {
        KnowledgeStructuredAggregateApi api = mock(KnowledgeStructuredAggregateApi.class);
        PatentStructuredAggregatePushdownAdapter adapter = new PatentStructuredAggregatePushdownAdapter(api);
        assertTrue(!adapter.supports(titleLengthPlan(Operation.AVG)));
    }

    private StructuredPipelinePlan countPlan() {
        return StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .aggregate(new StructuredAggregateSpec(Operation.COUNT, null,
                        PatentStructuredPack.METRIC_PATENT_COUNT))
                .build();
    }

    private StructuredPipelinePlan titleLengthPlan(Operation operation) {
        return StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .aggregate(new StructuredAggregateSpec(operation,
                        new StructuredValueExpression(PatentStructuredPack.FIELD_TITLE, false,
                                List.of(StructuredValueTransform.LENGTH)), null))
                .build();
    }

    private StructuredOrderRespDTO orderProof(Long id, long source, long missing, long conflicts) {
        StructuredOrderRespDTO dto = new StructuredOrderRespDTO();
        dto.setDocumentIds(id == null ? List.of() : List.of(id));
        dto.setSourceEntityCount(source);
        dto.setMissingValueCount(missing);
        dto.setConflictCount(conflicts);
        dto.setCompleteDataset(true);
        return dto;
    }

    private StructuredQueryRespDTO page(long id, String title) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setTitle(title);
        StructuredQueryRespDTO dto = new StructuredQueryRespDTO();
        dto.setRows(List.of(row));
        dto.setTruncated(false);
        return dto;
    }
}
