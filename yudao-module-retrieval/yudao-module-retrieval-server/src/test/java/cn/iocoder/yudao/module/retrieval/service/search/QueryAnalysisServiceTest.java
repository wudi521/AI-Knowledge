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
                "CN 122621758 A 一共有几项权利要求？", null, List.of(), new PatentDomainQueryPolicy());

        assertTrue(analysis.isSuccess());
        assertEquals("BIBLIOGRAPHIC_LOOKUP", analysis.getIntent());
        assertEquals("EXACT_METADATA", analysis.getRoute());
        assertEquals("CN 122621758 A", analysis.getPublicationNo());
        assertEquals(List.of(PatentQueryPreParser.META_CLAIM_COUNT), analysis.getMetadataFields());
        verify(modelApi, never()).chat(any(ModelChatReqDTO.class));
    }

    @Test
    void exactPatentClaimBypassesAnalysisLlm() {
        QueryAnalysis analysis = service.analyze(
                "申请号 202311832214.0 的权利要求8引用了哪些在先权利要求？",
                null, List.of(), new PatentDomainQueryPolicy());

        assertTrue(analysis.isSuccess());
        assertEquals("CLAIM_DEPENDENCY", analysis.getIntent());
        assertEquals("EXACT_CLAIM", analysis.getRoute());
        assertEquals("202311832214.0", analysis.getApplicationNo());
        assertEquals(8, analysis.getClaimNo());
        verify(modelApi, never()).chat(any(ModelChatReqDTO.class));
    }

    @Test
    void claimSummaryMapsToClaimSummaryIntent() {
        QueryAnalysis analysis = service.analyze(
                "申请号 202311042981.1 的权利要求1主要限定什么？",
                null, List.of(), new PatentDomainQueryPolicy());

        assertTrue(analysis.isSuccess());
        assertEquals("CLAIM_SUMMARY", analysis.getIntent());
        assertEquals("EXACT_CLAIM", analysis.getRoute());
        assertEquals("SUMMARY", analysis.getClaimQueryType());
        assertEquals(1, analysis.getClaimNo());
    }

    @Test
    void multiTurnInheritsPatentIdentifierFromHistory() {
        cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO first = new cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO();
        first.setRole("USER");
        first.setContent("申请号 202311042981.1 的核心技术方案是什么？");
        cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO second = new cn.iocoder.yudao.module.retrieval.api.dto.ChatTurnDTO();
        second.setRole("USER");
        second.setContent("它主要解决什么问题？");

        QueryAnalysis analysis = service.analyze("它主要解决什么问题？",
                List.of(first, second), List.of(), new PatentDomainQueryPolicy());

        // P0-07 多轮: 从历史继承申请号, 保持目标文档 Scope, 不丢失 identifier
        assertEquals("202311042981.1", analysis.getApplicationNo());
        assertEquals("SCOPED_RAG", analysis.getRoute());
    }
}
