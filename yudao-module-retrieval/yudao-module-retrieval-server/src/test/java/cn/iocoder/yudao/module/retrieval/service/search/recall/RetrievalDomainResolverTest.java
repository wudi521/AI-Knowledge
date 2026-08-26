package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalDomainResolverTest {

    @Test
    void registryFailureMustNotMasqueradeAsGeneralDomain() {
        KnowledgeApi api = mock(KnowledgeApi.class);
        when(api.getKbDomainCodes(anyList())).thenThrow(new RuntimeException("registry down"));

        RetrievalDomainResolver.Resolution result = new RetrievalDomainResolver(api)
                .resolveWithStatus(null, List.of(9L));

        assertTrue(result.failed());
        assertEquals("GENERAL", result.domainCode());
    }

    @Test
    void singleRegistryDomainIsResolvedExactly() {
        KnowledgeApi api = mock(KnowledgeApi.class);
        when(api.getKbDomainCodes(anyList())).thenReturn(CommonResult.success(Map.of(9L, "PATENT")));

        RetrievalDomainResolver.Resolution result = new RetrievalDomainResolver(api)
                .resolveWithStatus(null, List.of(9L));

        assertFalse(result.failed());
        assertFalse(result.mixedDomainScope());
        assertEquals("PATENT", result.domainCode());
    }

    @Test
    void mixedDomainScopeUsesOnlyGenericPluginsAndIsMarkedDegraded() {
        KnowledgeApi api = mock(KnowledgeApi.class);
        when(api.getKbDomainCodes(anyList())).thenReturn(CommonResult.success(Map.of(9L, "PATENT", 10L, "CONTRACT")));

        RetrievalDomainResolver.Resolution result = new RetrievalDomainResolver(api)
                .resolveWithStatus(null, List.of(9L, 10L));

        assertFalse(result.failed());
        assertTrue(result.mixedDomainScope());
        assertEquals("GENERAL", result.domainCode());
    }
}
