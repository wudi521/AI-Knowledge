package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredPipelineCapabilityDelegate;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredQueryCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueEvaluator;
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
 * Candidate IDs may narrow a deterministic structured read, but only the structured result can become verified.
 */
class StructuredCandidateEntityScopeIntegrationTest {

    @Test
    void retrievalCandidateFeedsStructuredEntityScopeWithoutTrustEscalation() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        // Deliberately return the whole mock KB even when the request contains resolvedEntityIds.
        // Core must enforce the subset again instead of trusting every adapter implementation.
        data.setRows(new ArrayList<>(List.of(
                row(1L, "无关专利一", "P-1", "张三"),
                row(2L, "一种代替印花的运动服", "202311042981.1", "孙新玲"),
                row(3L, "无关专利二", "P-3", "李四")
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

        CandidateCapability candidates = new CandidateCapability(List.of(2L));
        CapabilityInvoker invoker = new CapabilityInvoker(
                new CapabilityRegistry(List.of(candidates, structured), List.of()));
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan(
                    "candidate-detail-plan",
                    "帮我检索出来体替代印花的专利详情信息",
                    0,
                    List.of(
                            new PlanNode("resolve", "candidate-source", Map.of(),
                                    "定位用户近似称呼可能对应的实体", Set.of()),
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
                            ), "读取候选实体的结构化详情并验证实体", Set.of("resolve"))
                    ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(
                    plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, PatentStructuredPack.DOMAIN_CODE,
                            "trace-candidate-scope"),
                    new AgentExecutionBudget(4, 4, 5_000L));

            assertThat(result.status()).isEqualTo(CapabilityResultStatus.SUCCESS);
            assertThat(result.nodeResults().get("resolve").success()).isTrue();
            assertThat(result.nodeResults().get("detail").success()).isTrue();

            ReferenceRecord candidateReference = result.references().stream()
                    .filter(reference -> "resolve".equals(reference.nodeId()))
                    .findFirst().orElseThrow();
            assertThat(candidateReference.candidateEntityIds()).containsExactly(2L);
            assertThat(candidateReference.verifiedEntityIds()).isEmpty();

            ReferenceRecord detailReference = result.references().stream()
                    .filter(reference -> "detail".equals(reference.nodeId()))
                    .findFirst().orElseThrow();
            assertThat(detailReference.verifiedEntityIds()).containsExactly(2L);
            assertThat(detailReference.deterministicAnswer())
                    .contains("一种代替印花的运动服")
                    .contains("202311042981.1")
                    .contains("孙新玲")
                    .doesNotContain("无关专利一")
                    .doesNotContain("无关专利二");
            assertThat(detailReference.metadata())
                    .containsEntry("entityScopeApplied", true)
                    .containsEntry("inputEntityScopeCount", 1);
        } finally {
            invoker.shutdown();
        }
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
        row.setApplicant("测试申请人");
        row.setValue(1D);
        return row;
    }

    private static final class CandidateCapability implements KnowledgeCapability {
        private final List<Long> ids;
        private final CapabilityDefinition definition = new CapabilityDefinition(
                "candidate-source", "1", "测试候选实体来源", Set.of(), true, 1_000L, 20);

        private CandidateCapability(List<Long> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            AgentCapabilityOutput output = new AgentCapabilityOutput() {
                @Override public String summary() { return "candidate ids=" + ids; }
                @Override public String progressHash() { return "candidate:" + ids; }
                @Override public List<Long> candidateEntityIds() { return ids; }
                @Override public List<Long> verifiedEntityIds() { return List.of(); }
            };
            return CapabilityResult.success(output, Map.of(
                    "candidateEntityCount", ids.size(),
                    "completeDataset", false,
                    "outputComplete", true));
        }
    }
}
