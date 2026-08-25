package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.StructuredPipelineCapabilityDelegate;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueEvaluator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Domain Schema 声明即契约：声明可排序/分组/过滤/变换/指标运算后，Agent Pipeline 必须真实可执行。
 */
@ExtendWith(MockitoExtension.class)
class PatentStructuredSchemaContractTest {

    @Mock KnowledgeApi knowledgeApi;

    private DefaultDomainMetricRegistry metrics;
    private DefaultDomainFieldRegistry fields;
    private PatentStructuredDataAdapter adapter;
    private StructuredPipelineCapabilityDelegate delegate;
    private StructuredValueEvaluator evaluator;
    private CapabilityInvocationContext context;

    @BeforeEach
    void setUp() {
        metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        adapter = new PatentStructuredDataAdapter(knowledgeApi);
        evaluator = new StructuredValueEvaluator(fields);
        StructuredPipelineExecutor executor = new StructuredPipelineExecutor(fields, metrics, List.of(adapter), evaluator);
        delegate = new StructuredPipelineCapabilityDelegate(fields, metrics, entities, executor);
        context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "ag-schema-contract");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(1L, "短标题", "202300000001.1", "CN1A", "2023-01-01", "2023-02-01", "张三、李四", "甲公司", 3d),
                row(2L, "更长一些的标题", "202300000002.2", "CN2A", "2024-03-01", "2024-04-01", "张伟、欧阳明", "乙公司", 8d)
        )));
        // 部分纯 Registry 契约测试不会访问数据源；lenient 避免 Mockito 把这种共享 fixture 当成失败。
        lenient().when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));
    }

    @Test
    void everyRegisteredFieldAndMetricHasExecutableAdapter() {
        for (FieldDefinition field : fields.all("PATENT")) {
            assertThat(adapter.supports(field.getFieldCode()))
                    .as("registered field must be executable: %s", field.getFieldCode()).isTrue();
        }
        for (MetricDefinition metric : metrics.all("PATENT")) {
            assertThat(adapter.supports(metric.getMetricCode()))
                    .as("registered metric must be executable: %s", metric.getMetricCode()).isTrue();
        }
    }

    @Test
    void everyDeclaredSortableFieldActuallySorts() {
        List<FieldDefinition> sortable = fields.all("PATENT").stream()
                .filter(FieldDefinition::isSortable)
                .sorted(Comparator.comparing(FieldDefinition::getFieldCode)).toList();
        for (FieldDefinition field : sortable) {
            for (String direction : List.of("ASC", "DESC")) {
                CapabilityResult result = delegate.execute(context, Map.of(
                        "select", List.of(field.getFieldCode()),
                        "orderBy", Map.of("field", field.getFieldCode(), "direction", direction),
                        "limit", 1
                ));
                assertThat(result.success()).as("sortable=true must execute: %s %s -> %s",
                        field.getFieldCode(), direction, result.message()).isTrue();
            }
        }
    }

    @Test
    void everyDeclaredGroupableFieldActuallyGroups() {
        List<FieldDefinition> groupable = fields.all("PATENT").stream()
                .filter(FieldDefinition::isGroupable)
                .sorted(Comparator.comparing(FieldDefinition::getFieldCode)).toList();
        for (FieldDefinition field : groupable) {
            CapabilityResult result = delegate.execute(context, Map.of(
                    "groupBy", Map.of("field", field.getFieldCode(), "explode", field.isMultiValue()),
                    "aggregate", Map.of("operation", "COUNT")
            ));
            assertThat(result.success()).as("groupable=true must execute: %s -> %s",
                    field.getFieldCode(), result.message()).isTrue();
        }
    }

    @Test
    void everyDeclaredFilterOperatorActuallyExecutes() {
        for (FieldDefinition field : fields.all("PATENT")) {
            if (!field.isFilterable() || field.getAllowedOperators() == null) continue;
            for (FilterOperator operator : field.getAllowedOperators()) {
                Map<String, Object> condition = new java.util.LinkedHashMap<>();
                condition.put("field", field.getFieldCode());
                condition.put("operator", operator.name());
                condition.put("explode", field.isMultiValue());
                if (operator != FilterOperator.EXISTS) condition.put("values", expectedValues(field, operator));
                CapabilityResult result = delegate.execute(context, Map.of(
                        "select", List.of(field.getFieldCode()),
                        "filter", condition,
                        "limit", 10
                ));
                assertThat(result.success()).as("declared operator must execute: %s.%s -> %s",
                        field.getFieldCode(), operator, result.message()).isTrue();
            }
        }
    }

    @Test
    void everyDeclaredTransformIsAcceptedAndExecutable() {
        for (FieldDefinition field : fields.all("PATENT")) {
            if (field.getAllowedTransforms() == null) continue;
            for (var transform : field.getAllowedTransforms()) {
                StructuredValueExpression expression = new StructuredValueExpression(
                        field.getFieldCode(), field.isMultiValue(), List.of(transform));
                assertThat(evaluator.validate("PATENT", expression).valid())
                        .as("transform schema must validate: %s.%s", field.getFieldCode(), transform).isTrue();

                CapabilityResult result = delegate.execute(context, Map.of(
                        "aggregate", Map.of(
                                "operation", "COUNT_DISTINCT",
                                "field", field.getFieldCode(),
                                "explode", field.isMultiValue(),
                                "transforms", List.of(transform.name())
                        )
                ));
                assertThat(result.success()).as("declared transform must execute: %s.%s -> %s",
                        field.getFieldCode(), transform, result.message()).isTrue();
            }
        }
    }

    @Test
    void everyDeclaredMetricOperationActuallyExecutes() {
        for (MetricDefinition metric : metrics.all("PATENT")) {
            if (metric.getSupportedOperations() == null) continue;
            for (Operation operation : metric.getSupportedOperations()) {
                CapabilityResult result = delegate.execute(context, Map.of(
                        "aggregate", Map.of("operation", operation.name(), "metric", metric.getMetricCode())
                ));
                assertThat(result.success()).as("declared metric operation must execute: %s.%s -> %s",
                        metric.getMetricCode(), operation, result.message()).isTrue();
            }
        }
    }

    private List<String> expectedValues(FieldDefinition field, FilterOperator operator) {
        if (operator == FilterOperator.BETWEEN) {
            if ("DATE".equalsIgnoreCase(field.getValueType())) return List.of("2022-01-01", "2025-12-31");
            return List.of("0", "9999999999");
        }
        if ("DATE".equalsIgnoreCase(field.getValueType())) return List.of("2023-01-01");
        return switch (field.getFieldCode()) {
            case PatentStructuredPack.FIELD_APPLICATION_NO -> List.of("202300000001.1");
            case PatentStructuredPack.FIELD_PUBLICATION_NO -> List.of("CN1A");
            case PatentStructuredPack.FIELD_TITLE -> List.of("短标题");
            case PatentStructuredPack.FIELD_INVENTOR -> List.of("张三");
            case PatentStructuredPack.FIELD_APPLICANT -> List.of("甲公司");
            default -> List.of("测试");
        };
    }

    private StructuredQueryRowDTO row(Long id, String title, String app, String pub,
                                      String filingDate, String publicationDate, String inventors,
                                      String applicant, Double metricValue) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(title + ".pdf");
        row.setTitle(title);
        row.setApplicationNo(app);
        row.setPublicationNo(pub);
        row.setFilingDate(filingDate);
        row.setPublicationDate(publicationDate);
        row.setInventor(inventors);
        row.setApplicant(applicant);
        row.setValue(metricValue);
        return row;
    }
}
