package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEngineV3ExactLookupTest {

    @Test
    void titleContainsUsesPatentMetadataTitleInsteadOfUploadedFileName() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        new PatentStructuredPack(metrics, new DefaultDomainEntityRegistry(), fields);

        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        QueryPlannerV3 planner = mock(QueryPlannerV3.class);
        QueryIntentValidatorV3 validator = new QueryIntentValidatorV3(fields, metrics);
        PatentStructuredDataAdapter patentAdapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryExecutor structuredExecutor = new StructuredQueryExecutor(metrics, List.of(patentAdapter));
        QueryEngineV3 engine = new QueryEngineV3(planner, validator,
                mock(RetrievalRefinementService.class), mock(PlannedEvidenceRetriever.class),
                structuredExecutor, fields, metrics, List.of(), List.of(patentAdapter),
                knowledgeApi, mock(AnswerPipeline.class));

        QueryIntentV3 intent = QueryIntentV3.builder()
                .version("3").domainCode("PATENT").entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .plannerStatus(QueryIntentV3.PlannerStatus.EXECUTABLE).plannerSource("LLM")
                .selection(QueryIntentV3.Selection.builder()
                        .type(QueryIntentV3.SelectionType.STRUCTURED_FILTER)
                        .field(PatentStructuredPack.FIELD_TITLE)
                        .operator(FilterOperator.CONTAINS).operatorRaw("CONTAINS")
                        .values(List.of("磁涌")).build())
                .actions(List.of(QueryIntentV3.Action.builder().type(QueryIntentV3.ActionType.LIST).build()))
                .completeness("BEST_EFFORT").build();
        when(planner.plan(any(), any(), any(), any(), any())).thenReturn(intent);

        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(67L);
        row.setDocumentName("2023118322140.pdf");
        row.setTitle("一种粒子化磁涌装置及其使用方法");
        row.setApplicationNo("202311832214.0");
        row.setPublicationNo("CN 122619519 A");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(row));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        KnowledgeDocumentRespDTO document = new KnowledgeDocumentRespDTO();
        document.setId(67L);
        document.setName("2023118322140.pdf");
        document.setDomainMetadata("{\"domainCode\":\"PATENT\",\"title\":\"一种粒子化磁涌装置及其使用方法\",\"applicationNo\":\"202311832214.0\"}");
        when(knowledgeApi.getDocumentMap(any())).thenReturn(CommonResult.success(Map.of(67L, document)));

        QueryEngineV3.Result result = engine.execute(
                "标题包含“磁涌”的专利有哪些？",
                List.of(6L), "PATENT", List.of(), List.of(), 1L, 1L, "trace-title");

        assertThat(result.state()).isEqualTo(QueryEngineV3.State.ANSWER);
        assertThat(result.entityIds()).containsExactly(67L);
        assertThat(result.answer()).contains("一种粒子化磁涌装置及其使用方法")
                .doesNotContain("2023118322140.pdf")
                .doesNotContain("没有匹配对象");
        assertThat(result.selectionGuarantee()).isEqualTo("STRUCTURED_COMPLETE");
    }

    @Test
    void applicationNumberProjectionUsesDeterministicIndexedEntityPathEndToEnd() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        new PatentStructuredPack(metrics, new DefaultDomainEntityRegistry(), fields);

        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        ModelApi modelApi = mock(ModelApi.class);
        PromptSupport promptSupport = mock(PromptSupport.class);
        QueryIntentValidatorV3 validator = new QueryIntentValidatorV3(fields, metrics);
        DeterministicQueryPlannerV3 deterministic = new DeterministicQueryPlannerV3(fields);
        QueryPlannerV3 planner = new QueryPlannerV3(
                fields, metrics, modelApi, promptSupport, validator, deterministic);

        PatentStructuredDataAdapter patentAdapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryExecutor structuredExecutor = new StructuredQueryExecutor(metrics, List.of(patentAdapter));
        QueryEngineV3 engine = new QueryEngineV3(planner, validator,
                mock(RetrievalRefinementService.class), mock(PlannedEvidenceRetriever.class),
                structuredExecutor, fields, metrics, List.of(), List.of(patentAdapter),
                knowledgeApi, mock(AnswerPipeline.class));

        when(knowledgeApi.lookupPatentDocuments(any()))
                .thenReturn(CommonResult.success(List.of(67L)));
        when(knowledgeApi.getDocumentMap(any()))
                .thenReturn(CommonResult.success(Map.<Long, KnowledgeDocumentRespDTO>of()));
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(67L);
        row.setDocumentName("一种粒子化磁涌装置及其使用方法");
        row.setApplicationNo("202311832214.0");
        row.setPublicationNo("CN 122619519 A");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(row));
        data.setTruncated(false);
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        QueryEngineV3.Result result = engine.execute(
                "申请号 202311832214.0 的公布号是什么？",
                List.of(6L), "PATENT", List.of(), List.of(), 1L, 1L, "trace-exact");

        assertThat(result.state()).isEqualTo(QueryEngineV3.State.ANSWER);
        assertThat(result.executionMode()).isEqualTo("STRUCTURED");
        assertThat(result.entityIds()).containsExactly(67L);
        assertThat(result.answer()).contains("公布号").contains("CN 122619519 A");
        assertThat(result.stages()).anySatisfy(stage -> {
            assertThat(stage.getStage()).isEqualTo("PLAN_VALIDATE");
            assertThat(stage.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(stage.getOutputSummary()).isEqualTo("PASS");
        }).anySatisfy(stage -> assertThat(stage.getStage()).isEqualTo("EXACT_ENTITY_SELECT"));
        verify(modelApi, never()).chat(any());
    }
}
