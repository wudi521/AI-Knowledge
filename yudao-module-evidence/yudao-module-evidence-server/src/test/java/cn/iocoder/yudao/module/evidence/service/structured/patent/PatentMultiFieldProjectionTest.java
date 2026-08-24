package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentMultiFieldProjectionTest {

    @Mock KnowledgeApi knowledgeApi;

    @Test
    void applicationAndPublicationNumberAreProjectedInSameEntityRow() {
        StructuredQueryRowDTO source = new StructuredQueryRowDTO();
        source.setDocumentId(67L);
        source.setDocumentName("一种粒子化磁涌装置及其使用方法");
        source.setApplicationNo("202311832214.0");
        source.setPublicationNo("CN 122619519 A");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(source));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryPlan plan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY")
                .queryType(QueryType.LIST)
                .domainCode("PATENT")
                .entityType("PATENT_DOCUMENT")
                .scope(QueryScope.currentKb(6L))
                .metricCode(PatentStructuredPack.FIELD_APPLICATION_NO)
                .fieldCode(PatentStructuredPack.FIELD_APPLICATION_NO)
                .projections(List.of(PatentStructuredPack.FIELD_APPLICATION_NO,
                        PatentStructuredPack.FIELD_PUBLICATION_NO))
                .operation(Operation.NONE)
                .filters(Map.of("publishedOnly", "true"))
                .build();

        StructuredQueryResult result = adapter.execute(plan);

        assertThat(result.isUnsupported()).isFalse();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getFields())
                .containsEntry(PatentStructuredPack.FIELD_APPLICATION_NO, "202311832214.0")
                .containsEntry(PatentStructuredPack.FIELD_PUBLICATION_NO, "CN 122619519 A");

        ArgumentCaptor<cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO> captor =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO.class);
        verify(knowledgeApi).structuredQuery(captor.capture());
        assertThat(captor.getValue().getFieldCode()).isEqualTo(PatentStructuredPack.FIELD_APPLICATION_NO);
    }
}
