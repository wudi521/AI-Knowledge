package cn.iocoder.yudao.module.retrieval.service.search.scope;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatentExactIdentifierScopePluginTest {

    @Test
    void exactIdentifierNarrowsExistingHardScope() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        when(knowledgeApi.lookupPatentDocuments(any(PatentDocumentLookupReqDTO.class)))
                .thenReturn(CommonResult.success(List.of(12L)));
        PatentExactIdentifierScopePlugin plugin = new PatentExactIdentifierScopePlugin(
                new PatentQueryPreParser(), knowledgeApi);

        RetrievalScopeDecision decision = plugin.refine(new RetrievalScopeContext(
                "申请号 202311832214.0 的技术方案是什么？", 1L, List.of(9L), List.of(11L, 12L), "PATENT"));

        assertTrue(decision.applied());
        assertFalse(decision.blocked());
        assertEquals(List.of(12L), decision.documentIds());
    }

    @Test
    void exactIdentifierWithNoAuthoritativeDocumentFailsClosed() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        when(knowledgeApi.lookupPatentDocuments(any(PatentDocumentLookupReqDTO.class)))
                .thenReturn(CommonResult.success(List.of()));
        PatentExactIdentifierScopePlugin plugin = new PatentExactIdentifierScopePlugin(
                new PatentQueryPreParser(), knowledgeApi);

        RetrievalScopeDecision decision = plugin.refine(new RetrievalScopeContext(
                "申请号 202311832214.0 的技术方案是什么？", 1L, List.of(9L), List.of(), "PATENT"));

        assertTrue(decision.applied());
        assertTrue(decision.blocked());
        assertFalse(decision.degraded());
    }
}
