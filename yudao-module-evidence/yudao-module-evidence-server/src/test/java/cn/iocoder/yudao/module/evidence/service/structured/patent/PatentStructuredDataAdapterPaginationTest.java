package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredPageApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatentStructuredDataAdapterPaginationTest {

    @Test
    void productionPathMustReadAllPagesInsteadOfTreatingRowCapAsDatasetCap() {
        KnowledgeApi legacyApi = mock(KnowledgeApi.class);
        KnowledgeStructuredPageApi pageApi = mock(KnowledgeStructuredPageApi.class);
        AtomicInteger calls = new AtomicInteger();
        int total = 2505;

        when(pageApi.page(any(StructuredQueryReqDTO.class))).thenAnswer(invocation -> {
            StructuredQueryReqDTO request = invocation.getArgument(0);
            calls.incrementAndGet();
            long after = request.getAfterDocumentId() == null ? 0L : request.getAfterDocumentId();
            int pageSize = request.getRowCap() == null ? 1000 : request.getRowCap();
            int remaining = Math.max(0, total - (int) after);
            int count = Math.min(pageSize, remaining);
            List<StructuredQueryRowDTO> rows = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) rows.add(row(after + i));

            long last = after + count;
            StructuredQueryRespDTO response = new StructuredQueryRespDTO();
            response.setRows(rows);
            response.setTruncated(last < total);
            response.setNextDocumentId(last < total ? last : null);
            return CommonResult.success(response);
        });

        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(legacyApi, pageApi);
        StructuredQueryResult result = adapter.execute(plan());

        assertThat(result.isUnsupported()).isFalse();
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getRowCount()).isEqualTo(total);
        assertThat(result.getRows()).hasSize(total);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void nonAdvancingCursorMustFailClosed() {
        KnowledgeApi legacyApi = mock(KnowledgeApi.class);
        KnowledgeStructuredPageApi pageApi = mock(KnowledgeStructuredPageApi.class);
        when(pageApi.page(any(StructuredQueryReqDTO.class))).thenAnswer(invocation -> {
            StructuredQueryReqDTO request = invocation.getArgument(0);
            long after = request.getAfterDocumentId() == null ? 0L : request.getAfterDocumentId();
            StructuredQueryRespDTO response = new StructuredQueryRespDTO();
            response.setRows(List.of(row(after + 1)));
            response.setTruncated(true);
            response.setNextDocumentId(after); // deliberately broken: cursor never advances
            return CommonResult.success(response);
        });

        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(legacyApi, pageApi);
        StructuredQueryResult result = adapter.execute(plan());

        assertThat(result.isUnsupported()).isTrue();
        assertThat(result.getUnsupportedReason()).contains("cursor did not advance");
    }

    private StructuredQueryPlan plan() {
        return StructuredQueryPlan.builder()
                .route("TEST")
                .queryType(QueryType.LIST)
                .domainCode(PatentStructuredPack.DOMAIN_CODE)
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(9L))
                .fieldCode(PatentStructuredPack.FIELD_TITLE)
                .projections(List.of(PatentStructuredPack.FIELD_TITLE))
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .build();
    }

    private StructuredQueryRowDTO row(long id) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName("doc-" + id);
        row.setApplicationNo("APP-" + id);
        row.setPublicationNo("PUB-" + id);
        row.setTitle("title-" + id);
        row.setApplicant("applicant-" + id);
        row.setInventor("inventor-" + id);
        row.setFilingDate("2026-01-01");
        row.setPublicationDate("2026-02-01");
        return row;
    }
}
