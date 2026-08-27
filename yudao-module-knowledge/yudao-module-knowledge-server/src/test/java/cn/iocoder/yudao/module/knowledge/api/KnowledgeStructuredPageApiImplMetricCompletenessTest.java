package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 锁死数值指标的“缺失 != 0”语义，避免完整性证明出现假阳性。 */
class KnowledgeStructuredPageApiImplMetricCompletenessTest {

    @Test
    void missingClaimCountMustRemainNull() {
        AiDocumentMapper mapper = mock(AiDocumentMapper.class);
        when(mapper.selectStructuredPatentDocumentsPage(anyLong(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(document(1L, "{\"domainCode\":\"PATENT\",\"title\":\"A\"}")));

        KnowledgeStructuredPageApiImpl api = new KnowledgeStructuredPageApiImpl(mapper);
        StructuredQueryRespDTO response = api.page(request()).getCheckedData();

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getValue()).isNull();
    }

    @Test
    void explicitZeroClaimCountMustRemainZero() {
        AiDocumentMapper mapper = mock(AiDocumentMapper.class);
        when(mapper.selectStructuredPatentDocumentsPage(anyLong(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(document(1L,
                        "{\"domainCode\":\"PATENT\",\"title\":\"A\",\"claimCount\":0}")));

        KnowledgeStructuredPageApiImpl api = new KnowledgeStructuredPageApiImpl(mapper);
        StructuredQueryRespDTO response = api.page(request()).getCheckedData();

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getValue()).isEqualTo(0D);
    }

    private StructuredQueryReqDTO request() {
        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(9L);
        req.setDomainCode("PATENT");
        req.setMetricCode("CLAIM_COUNT");
        req.setPublishedOnly(true);
        return req;
    }

    private AiDocumentDO document(long id, String metadata) {
        return AiDocumentDO.builder()
                .id(id)
                .kbId(9L)
                .name("doc-" + id)
                .domainMetadata(metadata)
                .build();
    }
}
