package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceQueryScopeResolverTest {

    @Mock KnowledgeApi knowledgeApi;

    @Test
    void explicitUnauthorizedKbFailsClosedInsteadOfSilentlyDroppingIt() {
        EvidenceQueryScopeResolver resolver = new EvidenceQueryScopeResolver(knowledgeApi);
        when(knowledgeApi.getVisibleKbIds(9L)).thenReturn(CommonResult.success(Set.of(6L)));

        EvidenceQueryScopeResolver.Resolution r = resolver.resolve(List.of(6L, 7L), 9L, null);

        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).isEqualTo("KB_PERMISSION_DENIED");
    }

    @Test
    void emptyRequestUsesOnlyVisibleKbsAndAutoResolvesDomain() {
        EvidenceQueryScopeResolver resolver = new EvidenceQueryScopeResolver(knowledgeApi);
        when(knowledgeApi.getVisibleKbIds(9L)).thenReturn(CommonResult.success(Set.of(6L, 8L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L, 8L)))
                .thenReturn(CommonResult.success(Map.of(6L, "PATENT", 8L, "PATENT")));

        EvidenceQueryScopeResolver.Resolution r = resolver.resolve(List.of(), 9L, null);

        assertThat(r.allowed()).isTrue();
        assertThat(r.kbIds()).containsExactly(6L, 8L);
        assertThat(r.domainCode()).isEqualTo("PATENT");
    }

    @Test
    void mixedDomainsRequireUserToNarrowScope() {
        EvidenceQueryScopeResolver resolver = new EvidenceQueryScopeResolver(knowledgeApi);
        when(knowledgeApi.getVisibleKbIds(9L)).thenReturn(CommonResult.success(Set.of(6L, 8L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L, 8L)))
                .thenReturn(CommonResult.success(Map.of(6L, "PATENT", 8L, "PRODUCT")));

        EvidenceQueryScopeResolver.Resolution r = resolver.resolve(List.of(6L, 8L), 9L, null);

        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).isEqualTo("MIXED_DOMAIN_SCOPE");
    }

    @Test
    void callerSuppliedDomainCannotOverrideActualKbDomain() {
        EvidenceQueryScopeResolver resolver = new EvidenceQueryScopeResolver(knowledgeApi);
        when(knowledgeApi.getVisibleKbIds(9L)).thenReturn(CommonResult.success(Set.of(6L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L)))
                .thenReturn(CommonResult.success(Map.of(6L, "PATENT")));

        EvidenceQueryScopeResolver.Resolution r = resolver.resolve(List.of(6L), 9L, "PRODUCT");

        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).isEqualTo("DOMAIN_SCOPE_MISMATCH");
    }

    @Test
    void missingUserContextFailsClosed() {
        EvidenceQueryScopeResolver resolver = new EvidenceQueryScopeResolver(knowledgeApi);
        EvidenceQueryScopeResolver.Resolution r = resolver.resolve(List.of(6L), null, null);
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).isEqualTo("MISSING_USER_CONTEXT");
    }
}
