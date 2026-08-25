package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredPipelineProjectionSemanticsTest {

    @Test
    void explodedDistinctProjectionProducesIndividualValuesAndNoTrustedEntities() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        StructuredPipelineExecutor executor = new StructuredPipelineExecutor(fields, metrics,
                List.of(new PatentStructuredDataAdapter(knowledgeApi)), new StructuredValueEvaluator(fields));
        StructuredPipelineCapabilityDelegate delegate = new StructuredPipelineCapabilityDelegate(fields, metrics, entities, executor);

        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(1L, "A", "202300000001.1", "CN1A", "张三、李四"),
                row(2L, "B", "202300000002.2", "CN2A", "张三、王五")
        )));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        CapabilityResult result = delegate.execute(
                new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "projection-values"),
                Map.of(
                        "select", List.of(Map.of("field", "INVENTOR", "explode", true)),
                        "distinct", true,
                        "limit", 20
                ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.answer()).contains("张三").contains("李四").contains("王五");
        assertThat(output.rowSummary()).contains("INVENTOR|EXPLODE=张三")
                .contains("INVENTOR|EXPLODE=李四")
                .contains("INVENTOR|EXPLODE=王五");
        assertThat(output.entityIds()).isEmpty();
        assertThat(result.metadata().get("outputCount")).isEqualTo(3);
    }

    private StructuredQueryRowDTO row(Long id, String title, String app, String pub, String inventors) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(title + ".pdf");
        row.setTitle(title);
        row.setApplicationNo(app);
        row.setPublicationNo(pub);
        row.setFilingDate("2024-01-01");
        row.setPublicationDate("2024-02-01");
        row.setInventor(inventors);
        row.setApplicant("测试申请人");
        row.setValue(1d);
        return row;
    }
}
