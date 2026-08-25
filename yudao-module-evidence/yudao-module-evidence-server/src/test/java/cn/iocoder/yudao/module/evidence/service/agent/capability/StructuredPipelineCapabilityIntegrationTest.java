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

@ExtendWith(MockitoExtension.class)
class StructuredPipelineCapabilityIntegrationTest {

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
        context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-pipeline-test");
    }

    @Test
    void ordersBySortableDateFieldWithoutInventingMetric() {
        rows(
                row(1L, "较晚专利", "202300000001.1", "CN1A", "2024-05-03", "2024-06-01", "张三", 3d),
                row(2L, "最早专利", "202200000002.2", "CN2A", "2022-01-02", "2022-03-01", "李四", 5d),
                row(3L, "中间专利", "202300000003.3", "CN3A", "2023-08-10", "2023-09-01", "王五", 4d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE", "FILING_DATE"),
                "orderBy", Map.of("field", "FILING_DATE", "direction", "ASC"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(2L);
        assertThat(output.answer()).contains("最早专利").contains("2022-01-02");
    }

    @Test
    void countsDistinctDerivedSurnamesAcrossMultiValueInventors() {
        rows(
                row(1L, "A", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三、李四", 1d),
                row(2L, "B", "202300000002.2", "CN2A", "2023-01-02", "2023-02-02", "张伟、欧阳明", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "aggregate", Map.of(
                        "operation", "COUNT_DISTINCT",
                        "field", "INVENTOR",
                        "explode", true,
                        "transforms", List.of("PERSON_SURNAME")
                )
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.value()).isEqualTo(3d);
        assertThat(output.answer()).contains("发明人姓氏").contains("3");
        assertThat(output.entityIds()).isEmpty();
    }

    @Test
    void ordersByDerivedMultiValueCount() {
        rows(
                row(1L, "一位发明人", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三", 1d),
                row(2L, "三位发明人", "202300000002.2", "CN2A", "2023-01-02", "2023-02-02", "张三、李四、王五", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE", "INVENTOR"),
                "orderBy", Map.of("field", "INVENTOR", "transforms", List.of("VALUE_COUNT"), "direction", "DESC"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(2L);
        assertThat(output.answer()).contains("三位发明人");
    }

    @Test
    void groupsExplodedInventorsAndCountsLogicalPatents() {
        rows(
                row(1L, "A", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三、李四", 1d),
                row(2L, "B", "202300000002.2", "CN2A", "2023-01-02", "2023-02-02", "张三、王五", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "groupBy", Map.of("field", "INVENTOR", "explode", true),
                "aggregate", Map.of("operation", "COUNT"),
                "orderBy", Map.of("aggregateValue", true, "direction", "DESC")
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.answer()).contains("张三：2");
        assertThat(output.entityIds()).isEmpty();
    }

    @Test
    void groupsByDerivedYearAndOrdersByGroupDimension() {
        rows(
                row(1L, "2024专利", "202400000001.1", "CN1A", "2024-01-01", "2024-02-01", "张三", 1d),
                row(2L, "2022专利", "202200000002.2", "CN2A", "2022-03-01", "2022-04-01", "李四", 1d),
                row(3L, "2023专利", "202300000003.3", "CN3A", "2023-05-01", "2023-06-01", "王五", 1d)
        );

        Map<String, Object> yearExpr = Map.of("field", "FILING_DATE", "transforms", List.of("YEAR"));
        CapabilityResult result = delegate.execute(context, Map.of(
                "groupBy", yearExpr,
                "aggregate", Map.of("operation", "COUNT"),
                "orderBy", Map.of("field", "FILING_DATE", "transforms", List.of("YEAR"), "direction", "ASC")
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        int p2022 = output.answer().indexOf("2022");
        int p2023 = output.answer().indexOf("2023");
        int p2024 = output.answer().indexOf("2024");
        assertThat(p2022).isGreaterThanOrEqualTo(0);
        assertThat(p2023).isGreaterThan(p2022);
        assertThat(p2024).isGreaterThan(p2023);
    }

    @Test
    void failsClosedWhenFilterFieldIsMissingOnAnyLogicalEntity() {
        rows(
                row(1L, "有日期", "202300000001.1", "CN1A", "2024-05-03", "2024-06-01", "张三", 1d),
                row(2L, "缺日期", "202300000002.2", "CN2A", null, "2024-06-02", "李四", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "filter", Map.of("field", "FILING_DATE", "operator", "GTE", "values", List.of("2024-01-01")),
                "aggregate", Map.of("operation", "COUNT", "metric", "PATENT_COUNT")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.recoverable()).isFalse();
        assertThat(result.message()).contains("filter source is incomplete").contains("FILING_DATE");
    }

    @Test
    void explicitProjectionMissingValueMustNotBePresentedAsFullAnswer() {
        rows(
                row(1L, "有公开日", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三", 1d),
                row(2L, "缺公开日", "202300000002.2", "CN2A", "2023-01-02", null, "李四", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE", "PUBLICATION_DATE"),
                "limit", 10
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.recoverable()).isFalse();
        assertThat(result.message()).contains("PARTIAL").contains("missing");
    }

    @Test
    void invalidTypedFilterLiteralMustBeRejected() {
        CapabilityResult result = delegate.execute(context, Map.of(
                "filter", Map.of("field", "FILING_DATE", "operator", "GTE", "values", List.of("not-a-date")),
                "aggregate", Map.of("operation", "COUNT", "metric", "PATENT_COUNT")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("filter literal").contains("DATE");
    }

    @Test
    void ordersByDerivedTitleLength() {
        rows(
                row(1L, "短标题", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三", 1d),
                row(2L, "这是一个明显更长的专利标题", "202300000002.2", "CN2A", "2023-01-02", "2023-02-02", "李四", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE"),
                "orderBy", Map.of("field", "TITLE", "transforms", List.of("LENGTH"), "direction", "DESC"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(2L);
    }

    @Test
    void preservesMetricWhenOrderingMetricAndProjectingField() {
        rows(
                row(1L, "权项较少", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三", 3d),
                row(2L, "权项最多", "202300000002.2", "CN2A", "2023-01-02", "2023-02-02", "李四", 12d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE"),
                "orderBy", Map.of("metric", "CLAIM_COUNT", "direction", "DESC"),
                "limit", 1
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.entityIds()).containsExactly(2L);
        assertThat(output.answer()).contains("权项最多");
    }

    @Test
    void failsClosedWhenDuplicateLogicalPatentConflictsOnQueriedField() {
        rows(
                row(1L, "同一专利", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三", 1d),
                row(9L, "同一专利", "202300000001.1", "CN1A", "2024-01-01", "2023-02-01", "张三", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "select", List.of("TITLE", "FILING_DATE"),
                "orderBy", Map.of("field", "FILING_DATE", "direction", "ASC"),
                "limit", 1
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.recoverable()).isFalse();
        assertThat(result.message()).contains("FILING_DATE").contains("冲突");
    }

    @Test
    void duplicateLogicalPatentMultiValueOrderDifferenceIsNotConflict() {
        rows(
                row(1L, "同一专利", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三、李四", 1d),
                row(9L, "同一专利", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "李四,张三", 1d)
        );

        CapabilityResult result = delegate.execute(context, Map.of(
                "aggregate", Map.of(
                        "operation", "COUNT_DISTINCT",
                        "field", "INVENTOR",
                        "explode", true
                )
        ));

        assertThat(result.success()).isTrue();
        StructuredPipelineCapabilityDelegate.Output output = (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.value()).isEqualTo(2d);
    }

    private void rows(StructuredQueryRowDTO... rows) {
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(rows)));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));
    }

    private StructuredQueryRowDTO row(Long id, String title, String app, String pub,
                                      String filingDate, String publicationDate, String inventors,
                                      Double metricValue) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(title + ".pdf");
        row.setTitle(title);
        row.setApplicationNo(app);
        row.setPublicationNo(pub);
        row.setFilingDate(filingDate);
        row.setPublicationDate(publicationDate);
        row.setInventor(inventors);
        row.setApplicant("测试申请人");
        row.setValue(metricValue);
        return row;
    }
}
