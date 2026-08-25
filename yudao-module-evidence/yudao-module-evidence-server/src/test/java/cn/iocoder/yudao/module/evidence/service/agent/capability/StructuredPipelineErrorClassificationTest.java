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

class StructuredPipelineErrorClassificationTest {

    @Test
    void invalidPlannerLiteralIsRecoverableButMissingSourceDataIsNot() {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredPipelineExecutor executor = new StructuredPipelineExecutor(
                fields, metrics, List.of(adapter), new StructuredValueEvaluator(fields));
        StructuredPipelineCapabilityDelegate delegate = new StructuredPipelineCapabilityDelegate(
                fields, metrics, entities, executor);
        CapabilityInvocationContext context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "error-classification");

        StructuredQueryRespDTO complete = new StructuredQueryRespDTO();
        complete.setRows(new ArrayList<>(List.of(row(1L, "2024-05-03"))));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(complete));
        CapabilityResult invalidLiteral = delegate.execute(context, Map.of(
                "filter", Map.of("field", "FILING_DATE", "operator", "GTE", "values", List.of("not-a-date")),
                "aggregate", Map.of("operation", "COUNT", "metric", "PATENT_COUNT")
        ));

        assertThat(invalidLiteral.success()).isFalse();
        assertThat(invalidLiteral.recoverable()).isTrue();
        assertThat(invalidLiteral.message()).contains("invalid filter literal");

        StructuredQueryRespDTO incomplete = new StructuredQueryRespDTO();
        incomplete.setRows(new ArrayList<>(List.of(row(2L, null))));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(incomplete));
        CapabilityResult missingData = delegate.execute(context, Map.of(
                "filter", Map.of("field", "FILING_DATE", "operator", "GTE", "values", List.of("2024-01-01")),
                "aggregate", Map.of("operation", "COUNT", "metric", "PATENT_COUNT")
        ));

        assertThat(missingData.success()).isFalse();
        assertThat(missingData.recoverable()).isFalse();
        assertThat(missingData.message()).contains("filter source is incomplete");
    }

    private StructuredQueryRowDTO row(Long id, String filingDate) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName("测试" + id + ".pdf");
        row.setTitle("测试" + id);
        row.setApplicationNo("20230000000" + id + "." + id);
        row.setPublicationNo("CN" + id + "A");
        row.setFilingDate(filingDate);
        row.setPublicationDate("2024-06-01");
        row.setInventor("张三");
        row.setApplicant("测试申请人");
        row.setValue(1d);
        return row;
    }
}
