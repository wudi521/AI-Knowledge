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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 用户曾遇到的查询只作为能力矩阵验收样例；生产实现仍只使用通用 field/filter/select/order/transform Tool Contract。
 */
@ExtendWith(MockitoExtension.class)
class PatentPublicRuntimeCapabilityMatrixTest {

    @Mock KnowledgeApi knowledgeApi;

    private StructuredPipelineCapabilityDelegate delegate;
    private CapabilityInvocationContext context;

    @BeforeEach
    void setUp() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredValueEvaluator evaluator = new StructuredValueEvaluator(fields);
        StructuredPipelineExecutor executor = new StructuredPipelineExecutor(fields, metrics, List.of(adapter), evaluator);
        delegate = new StructuredPipelineCapabilityDelegate(fields, metrics, entities, executor);
        context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-public-matrix");
    }

    @Test
    void applicationNumberLookupProjectsPublicationNumberWithGenericFilterAndSelect() {
        rows(
                row(74L, "目标专利", "202311832214.0", "CN117000001A"),
                row(75L, "其他专利", "202311832215.5", "CN117000002A")
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "filter", Map.of("field", "APPLICATION_NO", "operator", "EQ",
                        "values", List.of("202311832214.0")),
                "select", List.of("PUBLICATION_NO"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(74L);
        assertThat(output.answer()).contains("CN117000001A");
    }

    @Test
    void titleContainsUsesGenericContainsOperatorInsteadOfBusinessIntentEnum() {
        rows(
                row(1L, "一种磁涌抑制装置", "202300000001.1", "CN1A"),
                row(2L, "普通通信装置", "202300000002.2", "CN2A"),
                row(3L, "磁涌冲击控制方法", "202300000003.3", "CN3A")
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "filter", Map.of("field", "TITLE", "operator", "CONTAINS", "values", List.of("磁涌")),
                "select", List.of("TITLE"),
                "limit", 20
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(1L, 3L);
        assertThat(output.answer()).contains("一种磁涌抑制装置").contains("磁涌冲击控制方法")
                .doesNotContain("普通通信装置");
    }

    @Test
    void longestTitleUsesLengthTransformOrderingInsteadOfLongestTitleIntent() {
        rows(
                row(1L, "短标题", "202300000001.1", "CN1A"),
                row(2L, "这是当前数据集中明显最长的专利标题", "202300000002.2", "CN2A"),
                row(3L, "中等长度专利标题", "202300000003.3", "CN3A")
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE"),
                "orderBy", Map.of("field", "TITLE", "transforms", List.of("LENGTH"), "direction", "DESC"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(2L);
        assertThat(output.answer()).contains("这是当前数据集中明显最长的专利标题");
    }

    private void rows(StructuredQueryRowDTO... rows) {
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(rows)));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));
    }

    private StructuredQueryRowDTO row(Long id, String title, String applicationNo, String publicationNo) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(title + ".pdf");
        row.setTitle(title);
        row.setApplicationNo(applicationNo);
        row.setPublicationNo(publicationNo);
        row.setFilingDate("2023-01-01");
        row.setPublicationDate("2023-02-01");
        row.setInventor("张三");
        row.setApplicant("测试申请人");
        row.setValue(1D);
        return row;
    }
}
