package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentDomainQueryPolicy;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import cn.iocoder.yudao.module.retrieval.service.prompt.PromptSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QueryAnalysisServiceTest {

    @Mock private ModelApi modelApi;
    @Mock private PromptSupport promptSupport;

    private QueryAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new QueryAnalysisService();
        ReflectionTestUtils.setField(service, "modelApi", modelApi);
        ReflectionTestUtils.setField(service, "promptSupport", promptSupport);
        ReflectionTestUtils.setField(service, "patentQueryPreParser", new PatentQueryPreParser());
    }

    @Test
    void exactPatentMetadataBypassesAnalysisLlm() {
        QueryAnalysis analysis = service.analyze(
                "CN 122621758 A 一共有几项权利要求？",
                null,
                List.of(),
                new PatentDomainQueryPolicy());

        assertTrue(analysis.isSuccess());
        assertEquals("BIBLIOGRAPHIC_LOOKUP", analysis.getIntent());
        assertEquals("EXACT_METADATA", analysis.getRoute());
        assertEquals("CN 122621758 A", analysis.getPublicationNo());
        assertEquals(List.of(PatentQueryPreParser.META_CLAIM_COUNT), analysis.getMetadataFields());
        verify(modelApi, never()).chat(any(ModelChatReqDTO.class));
    }
}
