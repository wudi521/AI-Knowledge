package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.GenerationResult;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.AssembledEvidence;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CQ-38 PER_ENTITY_SEMANTIC 语义执行: 逐实体 SCOPED_RAG + 聚合生成。
 * 约束: 实体数超限 → overLimit(禁止静默截断); 无证据 → 空集(不猜); 逐实体检索 hard scope 到单文档。
 */
class SemanticsExecutionServiceTest {

    private EvidenceAssembler assembler;
    private AnswerPipeline answerPipeline;
    private EvidenceProperties properties;
    private KnowledgeApi knowledgeApi;
    private SemanticsExecutionService service;

    @BeforeEach
    void setUp() {
        assembler = mock(EvidenceAssembler.class);
        answerPipeline = mock(AnswerPipeline.class);
        properties = new EvidenceProperties();
        knowledgeApi = mock(KnowledgeApi.class);
        service = new SemanticsExecutionService(assembler, answerPipeline, properties, knowledgeApi);
    }

    private Evidence ev(Long chunkId, String docId, String content) {
        return Evidence.builder().chunkId(chunkId).documentId(docId).content(content).build();
    }

    @Test
    void perEntityRetrieval_isScopedByDocumentId() {
        properties.getSemantics().setMaxSemanticEntities(10);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Long> docs = inv.getArgument(7);
            Long docId = docs.get(0);
            return new AssembledEvidence(docId == 101L
                    ? List.of(ev(1L, "101", "专利A核心技术内容"))
                    : List.of(ev(2L, "102", "专利B核心技术内容")),
                    List.of(), false, null, null, null);
        });
        when(answerPipeline.generateWithClaims(any(), any(), any())).thenReturn(
                GenerationResult.builder().answer("专利A：核心A；专利B：核心B").claims(List.of()).build());

        SemanticsExecutionService.Result r = service.execute("它们的技术方案分别是什么？", 6L,
                List.of(101L, 102L), 7L, 42L, List.of(), "q-test1");

        assertThat(r.overLimit()).isFalse();
        assertThat(r.entityIds()).containsExactly(101L, 102L);
        assertThat(r.evidences()).hasSize(2);
        assertThat(r.generation().getAnswer()).contains("核心A").contains("核心B");
        // 逐实体 hard scope: 每实体一次限定文档检索(单 kb + 单 documentId)
        verify(assembler).assemble(any(), eq(List.of(6L)), any(), any(), any(), any(), any(), eq(List.of(101L)));
        verify(assembler).assemble(any(), eq(List.of(6L)), any(), any(), any(), any(), any(), eq(List.of(102L)));
    }

    @Test
    void overLimit_returnsOverLimitFlagAndNoRetrieval() {
        properties.getSemantics().setMaxSemanticEntities(2);
        SemanticsExecutionService.Result r = service.execute("它们分别是什么", 6L,
                List.of(101L, 102L, 103L), 7L, 42L, List.of(), "q-test2");
        assertThat(r.overLimit()).isTrue();
        assertThat(r.limit()).isEqualTo(2);
        assertThat(r.entityIds()).containsExactly(101L, 102L, 103L);
        assertThat(r.evidences()).isNull();
        verifyNoInteractions(assembler, answerPipeline);
    }

    @Test
    void noEvidence_returnsEmptyAndSkipsGeneration() {
        properties.getSemantics().setMaxSemanticEntities(10);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AssembledEvidence.empty());
        SemanticsExecutionService.Result r = service.execute("它们的技术方案分别是什么", 6L,
                List.of(101L), 7L, 42L, List.of(), "q-test3");
        assertThat(r.overLimit()).isFalse();
        assertThat(r.evidences()).isEmpty();
        verifyNoInteractions(answerPipeline);
    }

    @Test
    void nullOrEmptyEntityIds_returnsEmpty() {
        SemanticsExecutionService.Result r = service.execute("它们分别是什么", 6L,
                List.of(), 7L, 42L, List.of(), "q-test4");
        assertThat(r.evidences()).isEmpty();
        verifyNoInteractions(assembler, answerPipeline);
    }

    @Test
    void executeCrossEntity_enumeratesPublishedDocsAndExecutesPerEntity() {
        // CQ-38 CROSS_ENTITY_SEMANTIC: 枚举 KB 已发布文档 → 逐实体 SCOPED_RAG
        properties.getSemantics().setMaxSemanticEntities(10);
        when(knowledgeApi.getPublishedDocumentIds(6L)).thenReturn(CommonResult.success(List.of(101L, 102L)));
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AssembledEvidence(List.of(ev(1L, "101", "支持无线充电")),
                        List.of(), false, null, null, null));
        when(answerPipeline.generateWithClaims(any(), any(), any()))
                .thenReturn(GenerationResult.builder()
                        .answer("手机A支持无线充电；手机B支持无线充电").claims(List.of()).build());

        SemanticsExecutionService.Result r = service.executeCrossEntity(
                "知识库有哪些产品支持无线充电？", 6L, 7L, 42L, List.of(), "q-test5");

        assertThat(r.overLimit()).isFalse();
        assertThat(r.entityIds()).containsExactly(101L, 102L);
        assertThat(r.generation().getAnswer()).contains("无线充电");
        verify(assembler, org.mockito.Mockito.times(2))
                .assemble(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeCrossEntity_enumerateRpcFailure_returnsEmpty() {
        properties.getSemantics().setMaxSemanticEntities(10);
        when(knowledgeApi.getPublishedDocumentIds(6L)).thenReturn(CommonResult.error(500, "err"));
        SemanticsExecutionService.Result r = service.executeCrossEntity(
                "知识库有哪些产品支持无线充电？", 6L, 7L, 42L, List.of(), "q-test6");
        assertThat(r.evidences()).isEmpty();
        verifyNoInteractions(assembler, answerPipeline);
    }
}
