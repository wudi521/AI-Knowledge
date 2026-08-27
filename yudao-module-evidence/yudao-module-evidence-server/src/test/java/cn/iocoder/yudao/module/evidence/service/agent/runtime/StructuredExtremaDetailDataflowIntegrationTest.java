package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
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

/** Regression for: find max/min group, then list detail fields for both winners. */
class StructuredExtremaDetailDataflowIntegrationTest {

    @Test
    void maxAndMinGroupKeysFeedOrFilterAndExplodedDetails() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(1L, "一种代替印花的运动服", "P-1", "孙新玲"),
                row(2L, "一种体外经颅式治疗仪", "P-2", "郝海涛、吴恒莉、贾少微、何昕"),
                row(3L, "两位发明人的中间专利", "P-3", "张三、李四")
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

        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(structuredQuery), List.of()));
        try {
            Map<String, Object> maxTitle = groupKeyRef("max");
            Map<String, Object> minTitle = groupKeyRef("min");
            AgentExecutionPlan plan = new AgentExecutionPlan(
                    "extrema-detail-chain",
                    "哪个专利发明人最多？哪个最少？罗列出来专利名字和发明人",
                    0,
                    List.of(
                            new PlanNode("max", StructuredQueryCapability.NAME,
                                    extremaArgs("DESC"), "确定发明人数量最多的专利", Set.of()),
                            new PlanNode("min", StructuredQueryCapability.NAME,
                                    extremaArgs("ASC"), "确定发明人数量最少的专利", Set.of()),
                            new PlanNode("details", StructuredQueryCapability.NAME, Map.of(
                                    "filter", Map.of(
                                            "logic", "OR",
                                            "children", List.of(
                                                    Map.of("field", "TITLE", "operator", "EQ", "values", maxTitle),
                                                    Map.of("field", "TITLE", "operator", "EQ", "values", minTitle)
                                            )
                                    ),
                                    "select", List.of(
                                            "TITLE",
                                            Map.of("field", "INVENTOR", "explode", true)
                                    ),
                                    "limit", 20
                            ), "罗列极值专利的专利名字和发明人", Set.of("max", "min"))
                    ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(
                    plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, PatentStructuredPack.DOMAIN_CODE,
                            "trace-extrema-detail"),
                    new AgentExecutionBudget(6, 6, 5_000L));

            assertThat(result.status()).isEqualTo(CapabilityResultStatus.SUCCESS);
            assertThat(result.nodeResults().values()).allSatisfy(node -> assertThat(node.success()).isTrue());

            List<?> maxRows = (List<?>) result.nodeResults().get("max").metadata()
                    .get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
            List<?> minRows = (List<?>) result.nodeResults().get("min").metadata()
                    .get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
            assertThat(((Map<?, ?>) maxRows.get(0)).get("groupKey")).isEqualTo("一种体外经颅式治疗仪");
            assertThat(((Map<?, ?>) minRows.get(0)).get("groupKey")).isEqualTo("一种代替印花的运动服");

            List<?> detailRows = (List<?>) result.nodeResults().get("details").metadata()
                    .get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
            assertThat(detailRows).hasSize(5);
            assertThat(detailRows).extracting(item -> ((Map<?, ?>) item).get("entityName"))
                    .containsOnly("一种代替印花的运动服", "一种体外经颅式治疗仪");

            ReferenceRecord detailReference = result.references().stream()
                    .filter(reference -> "details".equals(reference.nodeId()))
                    .findFirst().orElseThrow();
            assertThat(detailReference.deterministicAnswer())
                    .contains("罗列极值专利的专利名字和发明人：")
                    .contains("一种代替印花的运动服")
                    .contains("发明人=孙新玲")
                    .contains("一种体外经颅式治疗仪")
                    .contains("发明人=郝海涛、吴恒莉、贾少微、何昕")
                    .doesNotContain("筛选条件");
        } finally {
            invoker.shutdown();
        }
    }

    private Map<String, Object> extremaArgs(String direction) {
        return Map.of(
                "groupBy", "TITLE",
                "aggregate", Map.of(
                        "operation", "COUNT",
                        "field", "INVENTOR",
                        "explode", true
                ),
                "orderBy", Map.of("aggregateValue", true, "direction", direction),
                "limit", 1
        );
    }

    private Map<String, Object> groupKeyRef(String nodeId) {
        return Map.of(
                "$ref", nodeId,
                "selector", "metadata",
                "path", StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY + "[*].groupKey",
                "distinct", true,
                "required", true,
                "expect", "LIST",
                "allowPartial", true
        );
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
