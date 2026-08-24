package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CrossEntityCoverageGuard + Domain identity 去重回归。 */
@ExtendWith(MockitoExtension.class)
class SemanticsExecutionServiceCompareTest {

    @Mock EvidenceAssembler assembler;
    @Mock AnswerPipeline answerPipeline;
    @Mock KnowledgeApi knowledgeApi;

    private SemanticsExecutionService service;

    @BeforeEach
    void setUp() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getSemantics().setMaxSemanticEntities(10);
        service = new SemanticsExecutionService(assembler, answerPipeline, properties, knowledgeApi);
    }

    @Test
    void duplicateApplicationNumberCountsAsOneLogicalEntity() {
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    List<Long> ids = inv.getArgument(7);
                    Long id = ids.get(0);
                    String app = (id == 66L || id == 68L) ? "202311042981.1" : "APP-" + id;
                    Evidence e = Evidence.builder()
                            .chunkId(id * 10)
                            .documentId(String.valueOf(id))
                            .content("evidence-" + id)
                            .chunkMetadata("{\"applicationNo\":\"" + app + "\"}")
                            .build();
                    return new AssembledEvidence(List.of(e), List.of(), false, null, null, null);
                });
        when(answerPipeline.generateWithClaims(any(), any(), any()))
                .thenReturn(GenerationResult.builder().answer("ok").claimFail(false).build());

        SemanticsExecutionService.CompareResult result = service.executeCompare(
                "哪些专利比较相似？", 6L, List.of(65L, 66L, 67L, 68L), 1L, 1L, List.of(), "q-test", true);

        assertThat(result.coverageInsufficient()).isFalse();
        assertThat(result.entityIds()).containsExactly(65L, 66L, 67L);
        assertThat(result.coveredEntityIds()).containsExactly(65L, 66L, 67L);
        assertThat(result.evidences()).hasSize(3);
        assertThat(result.generation().getAnswer()).isEqualTo("ok");
    }

    @Test
    void missingEntityEvidenceBlocksGeneration() {
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    List<Long> ids = inv.getArgument(7);
                    Long id = ids.get(0);
                    if (id == 67L) return AssembledEvidence.empty();
                    Evidence e = Evidence.builder()
                            .chunkId(id * 10)
                            .documentId(String.valueOf(id))
                            .content("evidence-" + id)
                            .chunkMetadata("{\"applicationNo\":\"APP-" + id + "\"}")
                            .build();
                    return new AssembledEvidence(List.of(e), List.of(), false, null, null, null);
                });

        SemanticsExecutionService.CompareResult result = service.executeCompare(
                "这三个专利有什么共同点？", 6L, List.of(65L, 66L, 67L), 1L, 1L, List.of(), "q-test", true);

        assertThat(result.coverageInsufficient()).isTrue();
        assertThat(result.entityIds()).containsExactly(65L, 66L, 67L);
        assertThat(result.coveredEntityIds()).containsExactly(65L, 66L);
        verify(answerPipeline, never()).generateWithClaims(any(), any(), any());
    }
}
