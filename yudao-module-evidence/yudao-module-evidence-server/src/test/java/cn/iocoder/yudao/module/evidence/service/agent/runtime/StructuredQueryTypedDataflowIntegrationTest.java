package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredPipelineCapabilityDelegate;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredQueryCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
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
 * End-to-end regression for the production structured_query capability inside Agent Runtime.
 * It proves that a grouped/HAVING result can feed typed values into a dependent structured query.
 */
class StructuredQueryTypedDataflowIntegrationTest {

    @Test
    void multiInventorPatentGroupsFeedApplicationNumbersIntoInventorProjection() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(1L, "单发明人", "P-1", "张三"),
                row(2L, "双发明人", "P-2", "李四、王五"),
                row(3L, "三发明人", "P-3", "赵六、钱七、孙八")
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
        StructuredQueryCapability structuredQuery = new StructuredQueryCapability(
                fields, metrics, entities, null, null, delegate);

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(structuredQuery), List.of()));
        try {
            Map<String, Object> applicationNumbers = Map.of(
                    "$ref", "n1",
                    "selector", "metadata",
                    "path", StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY + "[*].groupKey",
                    "distinct", true,
                    "required", true,
                    "expect", "LIST"
            );
            AgentExecutionPlan plan = new AgentExecutionPlan(
                    "patent-inventor-chain",
                    "找出有多个发明人的专利，并列出这些专利的发明人",
                    0,
                    List.of(
                            new PlanNode("n1", StructuredQueryCapability.NAME, Map.of(
                                    "groupBy", "APPLICATION_NO",
                                    "aggregate", Map.of(
                                            "operation", "COUNT",
                                            "field", "INVENTOR",
                                            "explode", true
                                    ),
                                    "having", Map.of(
                                            "operator", "GT",
                                            "values", List.of(1)
                                    )
                            ), "按申请号统计发明人数并筛选多发明人专利", Set.of()),
                            new PlanNode("n2", StructuredQueryCapability.NAME, Map.of(
                                    "filter", Map.of(
                                            "field", "APPLICATION_NO",
                                            "operator", "IN",
                                            "values", applicationNumbers
                                    ),
                                    "select", List.of(
                                            "APPLICATION_NO",
                                            Map.of("field", "INVENTOR", "explode", true)
                                    ),
                                    "limit", 20
                            ), "列出这些专利的发明人", Set.of("n1"))
                    ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(
                    plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, PatentStructuredPack.DOMAIN_CODE,
                            "trace-patent-inventor-chain"),
                    new AgentExecutionBudget(6, 6, 5_000L));

            assertThat(result.status()).isEqualTo(CapabilityResultStatus.SUCCESS);
            CapabilityResult first = result.nodeResults().get("n1");
            CapabilityResult second = result.nodeResults().get("n2");
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.success()).isTrue();
            assertThat(second.success()).isTrue();

            List<?> groupedRows = (List<?>) first.metadata().get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
            assertThat(groupedRows).hasSize(2);
            assertThat(groupedRows).extracting(item -> ((Map<?, ?>) item).get("groupKey"))
                    .containsExactly("P-2", "P-3");

            List<?> inventorRows = (List<?>) second.metadata().get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
            assertThat(inventorRows).hasSize(5);
            assertThat(inventorRows).extracting(item -> ((Map<?, ?>) item).get("fields"))
                    .allSatisfy(rawFields -> {
                        Map<?, ?> projected = (Map<?, ?>) rawFields;
                        assertThat(projected.get("APPLICATION_NO")).isIn("P-2", "P-3");
                        assertThat(projected.get("INVENTOR|EXPLODE")).isNotNull();
                    });
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
        row.setFilingDate("2024-01-01");
        row.setPublicationDate("2024-02-01");
        row.setInventor(inventors);
        row.setApplicant("测试申请人");
        row.setValue(1D);
        return row;
    }
}
