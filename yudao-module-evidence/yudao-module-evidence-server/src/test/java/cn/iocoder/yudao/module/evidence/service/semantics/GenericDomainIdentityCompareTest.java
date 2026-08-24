package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Fake PRODUCT Domain：证明 Core 的跨实体比较/去重不依赖 Patent 字段。 */
@ExtendWith(MockitoExtension.class)
class GenericDomainIdentityCompareTest {

    @Mock EvidenceAssembler assembler;
    @Mock AnswerPipeline answerPipeline;
    @Mock KnowledgeApi knowledgeApi;

    @Test
    void fakeProductDomainUsesItsOwnIdentityProvider() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getSemantics().setMaxSemanticEntities(10);
        DomainEntityIdentityProvider productProvider = new DomainEntityIdentityProvider() {
            @Override public String domainCode() { return "PRODUCT"; }
            @Override public String identityKey(Evidence evidence, Long documentId) {
                if (evidence == null || evidence.getChunkMetadata() == null) return null;
                String sku = JSONUtil.parseObj(evidence.getChunkMetadata()).getStr("sku");
                return sku == null ? null : "PRODUCT:SKU:" + sku;
            }
        };
        SemanticsExecutionService service = new SemanticsExecutionService(
                assembler, answerPipeline, properties, knowledgeApi, List.of(productProvider));

        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    List<Long> ids = inv.getArgument(7);
                    Long id = ids.get(0);
                    String sku = (id == 10L || id == 11L) ? "SKU-A" : "SKU-B";
                    Evidence evidence = Evidence.builder()
                            .chunkId(id * 10)
                            .documentId(String.valueOf(id))
                            .content("product-" + id)
                            .chunkMetadata("{\"sku\":\"" + sku + "\"}")
                            .build();
                    return new AssembledEvidence(List.of(evidence), List.of(), false, null, null, null);
                });
        when(answerPipeline.generateWithClaims(any(), any(), any()))
                .thenReturn(GenerationResult.builder().answer("compare-ok").build());

        SemanticsExecutionService.CompareResult result = service.executeCompare(
                "哪些产品比较相似？", 1L, "PRODUCT", List.of(10L, 11L, 12L),
                1L, 1L, List.of(), "q-product", true);

        assertThat(result.coverageInsufficient()).isFalse();
        assertThat(result.entityIds()).containsExactly(10L, 12L);
        assertThat(result.coveredEntityIds()).containsExactly(10L, 12L);
        assertThat(result.generation().getAnswer()).isEqualTo("compare-ok");
    }
}
