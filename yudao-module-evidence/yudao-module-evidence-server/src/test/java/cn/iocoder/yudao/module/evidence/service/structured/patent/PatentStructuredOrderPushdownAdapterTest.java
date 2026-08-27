package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.SortDirection;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredOrderSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredOrderApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredPageApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentStructuredOrderPushdownAdapterTest {

    @Mock KnowledgeStructuredOrderApi orderApi;
    @Mock KnowledgeStructuredPageApi pageApi;

    private PatentStructuredOrderPushdownAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PatentStructuredOrderPushdownAdapter(orderApi, pageApi);
    }

    @Test
    void typedTitleLengthTopNUsesAuthoritativeOrderAndPreservesReturnedOrder() {
        StructuredOrderRespDTO proof = proof(List.of(99L, 88L), 9L, 0L, 0L);
        when(orderApi.order(any(StructuredOrderReqDTO.class))).thenReturn(CommonResult.success(proof));
        // Page API 按 documentId 返回也没关系；Adapter 必须按权威 order id 顺序重建结果。
        when(pageApi.page(any(StructuredQueryReqDTO.class))).thenReturn(CommonResult.success(page(
                row(88L, "第二长"),
                row(99L, "这是最长的专利标题"))));

        StructuredPushdownResult pushed = adapter.executePushdown(plan(SortDirection.DESC, 2));

        assertEquals(StructuredPushdownResult.Status.SUCCEEDED, pushed.status());
        assertTrue(pushed.result().success());
        assertTrue(pushed.result().completeDataset());
        assertEquals(List.of(99L, 88L), pushed.result().rows().stream().map(r -> r.entityId()).toList());
        assertEquals("KNOWLEDGE_SQL", pushed.result().metadata().get("pushdownBackend"));
        assertEquals("ORDER_TOP_N", pushed.result().metadata().get("pushdownOperation"));
        assertEquals(9, ((Number) pushed.result().metadata().get("sourceEntityCount")).intValue());
        assertTrue(Boolean.TRUE.equals(pushed.result().metadata().get("limited")));

        ArgumentCaptor<StructuredOrderReqDTO> orderReq = ArgumentCaptor.forClass(StructuredOrderReqDTO.class);
        verify(orderApi).order(orderReq.capture());
        assertEquals("TITLE", orderReq.getValue().getFieldCode());
        assertEquals("LENGTH", orderReq.getValue().getTransformCode());
        assertEquals("DESC", orderReq.getValue().getDirection());
    }

    @Test
    void missingTitleProofMustFailClosedWithoutMaterializingCandidate() {
        when(orderApi.order(any(StructuredOrderReqDTO.class)))
                .thenReturn(CommonResult.success(proof(List.of(), 9L, 1L, 0L)));

        StructuredPushdownResult pushed = adapter.executePushdown(plan(SortDirection.DESC, 1));

        assertEquals(StructuredPushdownResult.Status.FAILED, pushed.status());
        assertTrue(pushed.reason().contains("missing TITLE"));
        verify(pageApi, never()).page(any());
    }

    @Test
    void sqlConflictMustReturnUnsupportedSoJvmCanonicalConflictRulesCanDecide() {
        when(orderApi.order(any(StructuredOrderReqDTO.class)))
                .thenReturn(CommonResult.success(proof(List.of(), 9L, 0L, 1L)));

        StructuredPushdownResult pushed = adapter.executePushdown(plan(SortDirection.ASC, 1));

        assertEquals(StructuredPushdownResult.Status.UNSUPPORTED, pushed.status());
        verify(pageApi, never()).page(any());
    }

    @Test
    void unrelatedTypedPlanIsNotClaimedByThisAdapter() {
        StructuredPipelinePlan filingDatePlan = StructuredPipelinePlan.builder()
                .domainCode("PATENT")
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(StructuredValueExpression.field(PatentStructuredPack.FIELD_FILING_DATE)))
                .orderBy(List.of(new StructuredOrderSpec(
                        StructuredValueExpression.field(PatentStructuredPack.FIELD_FILING_DATE),
                        null, false, SortDirection.DESC)))
                .limit(1)
                .build();

        assertFalse(adapter.supports(filingDatePlan));
        verify(orderApi, never()).order(any());
    }

    @Test
    void titleOrderWithoutLengthTransformIsNotClaimed() {
        StructuredPipelinePlan rawTitlePlan = StructuredPipelinePlan.builder()
                .domainCode("PATENT")
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(StructuredValueExpression.field(PatentStructuredPack.FIELD_TITLE)))
                .orderBy(List.of(new StructuredOrderSpec(
                        StructuredValueExpression.field(PatentStructuredPack.FIELD_TITLE),
                        null, false, SortDirection.DESC)))
                .limit(1)
                .build();

        assertFalse(adapter.supports(rawTitlePlan));
    }

    private StructuredPipelinePlan plan(SortDirection direction, int limit) {
        StructuredValueExpression titleLength = new StructuredValueExpression(
                PatentStructuredPack.FIELD_TITLE, false, List.of(StructuredValueTransform.LENGTH));
        return StructuredPipelinePlan.builder()
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .select(List.of(StructuredValueExpression.field(PatentStructuredPack.FIELD_TITLE)))
                .orderBy(List.of(new StructuredOrderSpec(titleLength, null, false, direction)))
                .limit(limit)
                .build();
    }

    private StructuredOrderRespDTO proof(List<Long> ids, long source, long missing, long conflicts) {
        StructuredOrderRespDTO dto = new StructuredOrderRespDTO();
        dto.setDocumentIds(ids);
        dto.setSourceEntityCount(source);
        dto.setMissingValueCount(missing);
        dto.setConflictCount(conflicts);
        dto.setCompleteDataset(true);
        return dto;
    }

    private StructuredQueryRespDTO page(StructuredQueryRowDTO... rows) {
        StructuredQueryRespDTO response = new StructuredQueryRespDTO();
        response.setRows(List.of(rows));
        response.setTruncated(false);
        return response;
    }

    private StructuredQueryRowDTO row(long id, String title) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setTitle(title);
        return row;
    }
}
