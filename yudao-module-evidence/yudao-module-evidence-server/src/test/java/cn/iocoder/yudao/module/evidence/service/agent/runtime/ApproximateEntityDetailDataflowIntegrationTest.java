package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.DomainEvidenceEntityMapperRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeRetrievalCapability;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredPipelineCapabilityDelegate;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredQueryCapability;
import cn.iocoder.yudao.module.evidence.service.assemble.PlannedEvidenceRetriever;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueEvaluator;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentEvidenceEntityMapper;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for noisy/approximate entity wording: semantic retrieval resolves a ranked candidate,
 * then structured_query materializes authoritative detail without repeating the noisy literal as a filter.
 */
class ApproximateEntityDetailDataflowIntegrationTest {

    @Test
    void typoLikeNameResolvesTopCandidateThenMaterializesOnlyThatPatent() {
        PlannedEvidenceRetriever retriever = mock(PlannedEvidenceRetriever.class);
        List<Evidence> retrieved = List.of(
                evidence(201L, "2", "一种代替印花的运动服", "代替印花的运动服相关技术内容", 1.00D),
                evidence(202L, "2", "一种代替印花的运动服", "电脑绣代替印花，降低染料使用量", 0.92D),
                evidence(301L, "1", "一种体外经颅式治疗仪", "治疗仪技术内容", 0.71D),
                evidence(401L, "3", "一种多功能药物载体的制备方法", "药物载体技术内容", 0.63D)
        );
        when(retriever.search("体替代印花", List.of(), List.of(6L), null, 5,
                1L, 2L, PatentStructuredPack.DOMAIN_CODE, "trace-approx-detail"))
                .thenReturn(new PlannedEvidenceRetriever.Result(retrieved, null, null, null, null));

        KnowledgeRetrievalCapability retrieval = new KnowledgeRetrievalCapability(
                retriever,
                new DomainEvidenceEntityMapperRegistry(List.of(new PatentEvidenceEntityMapper())));

        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        // Deliberately return the whole mock KB. Structured Core must enforce entityIds again.
        data.setRows(new ArrayList<>(List.of(
                row(1L, "一种体外经颅式治疗仪", "202311066818.9", "郝海涛、吴恒莉"),
                row(2L, "一种代替印花的运动服", "202311042981.1", "孙新玲"),
                row(3L, "一种多功能药物载体的制备方法", "202410000003.0", "张三")
        )));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        StructuredPipelineExecutor pipelineExecutor = new StructuredPipelineExecutor(
                fields, metrics,
                List.of(new PatentStructuredDataAdapter(knowledgeApi)),
                new StructuredValueEvaluator(fields));
        StructuredPipelineCapabilityDelegate delegate = new StructuredPipelineCapabilityDelegate(
                fields, metrics, entities, pipelineExecutor);
        StructuredQueryCapability structured = new StructuredQueryCapability(
                fields, metrics, entities, null, null, delegate);

        CapabilityInvoker invoker = new CapabilityInvoker(
                new CapabilityRegistry(List.of(retrieval, structured), List.of()));
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan(
                    "approximate-detail-plan",
                    "帮我检索出来体替代印花的专利详情信息",
                    0,
                    List.of(
                            new PlanNode("resolve", KnowledgeRetrievalCapability.NAME, Map.of(
                                    "query", "体替代印花",
                                    "topK", 5,
                                    "candidateTopN", 1
                            ), "定位用户近似称呼最可能对应的专利候选", Set.of()),
                            new PlanNode("detail", StructuredQueryCapability.NAME, Map.of(
                                    "entityIds", Map.of(
                                            "$ref", "resolve",
                                            "selector", "candidateEntityIds",
                                            "required", true,
                                            "expect", "LIST"
                                    ),
                                    "select", List.of(
                                            "TITLE", "APPLICATION_NO", "PUBLICATION_NO",
                                            "APPLICANT", "INVENTOR", "FILING_DATE", "PUBLICATION_DATE"
                                    )
                            ), "读取候选专利的权威结构化详情", Set.of("resolve"))
                    ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(
                    plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, PatentStructuredPack.DOMAIN_CODE,
                            "trace-approx-detail"),
                    new AgentExecutionBudget(4, 4, 5_000L));

            assertThat(result.status()).isEqualTo(CapabilityResultStatus.SUCCESS);
            assertThat(result.nodeResults().get("resolve").success()).isTrue();
            assertThat(result.nodeResults().get("detail").success()).isTrue();

            ReferenceRecord retrievalReference = result.references().stream()
                    .filter(reference -> "resolve".equals(reference.nodeId()))
                    .findFirst().orElseThrow();
            assertThat(retrievalReference.candidateEntityIds()).containsExactly(2L);
            assertThat(retrievalReference.verifiedEntityIds()).isEmpty();
            assertThat(retrievalReference.evidences()).hasSize(2)
                    .allSatisfy(evidence -> assertThat(evidence.getDocumentId()).isEqualTo("2"));
            assertThat(retrievalReference.metadata())
                    .containsEntry("retrievedEvidenceCount", 4)
                    .containsEntry("evidenceCount", 2)
                    .containsEntry("candidateTopN", 1)
                    .containsEntry("candidateEvidenceScoped", true);

            ReferenceRecord detailReference = result.references().stream()
                    .filter(reference -> "detail".equals(reference.nodeId()))
                    .findFirst().orElseThrow();
            assertThat(detailReference.verifiedEntityIds()).containsExactly(2L);
            assertThat(detailReference.deterministicAnswer())
                    .contains("一种代替印花的运动服")
                    .contains("202311042981.1")
                    .contains("孙新玲")
                    .doesNotContain("体外经颅")
                    .doesNotContain("多功能药物载体")
                    .doesNotContain("未找到符合条件");
            assertThat(detailReference.metadata())
                    .containsEntry("entityScopeApplied", true)
                    .containsEntry("inputEntityScopeCount", 1);
        } finally {
            invoker.shutdown();
            retrieval.shutdown();
        }
    }

    private Evidence evidence(Long chunkId, String documentId, String name, String content, Double score) {
        return Evidence.builder().chunkId(chunkId).documentId(documentId).documentName(name)
                .content(content).score(score).products(List.of()).channels(List.of("vector")).build();
    }

    private StructuredQueryRowDTO row(Long id, String title, String applicationNo, String inventors) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(title + ".pdf");
        row.setTitle(title);
        row.setApplicationNo(applicationNo);
        row.setPublicationNo("CN-" + id);
        row.setFilingDate("2023-08-18");
        row.setPublicationDate("2026-08-21");
        row.setInventor(inventors);
        row.setApplicant("辽宁国科科技有限公司");
        row.setValue(1D);
        return row;
    }
}
