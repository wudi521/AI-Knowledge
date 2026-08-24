package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiFieldProjectionServiceTest {

    @Mock StructuredQueryExecutor executor;

    @Test
    void twoRegisteredFieldsProduceOneTypedProjectionPlan() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        fields.register(FieldDefinition.builder().domainCode("PRODUCT").entityType("ITEM")
                .fieldCode("CODE").valueType("STRING").aliases(List.of("编码")).build());
        fields.register(FieldDefinition.builder().domainCode("PRODUCT").entityType("ITEM")
                .fieldCode("NAME").valueType("STRING").aliases(List.of("名称")).build());

        StructuredQueryResult.Row row = StructuredQueryResult.Row.builder()
                .entityId(1L).entityName("商品A")
                .fields(Map.of("CODE", "P001", "NAME", "商品A"))
                .build();
        when(executor.execute(argThat(plan -> plan != null
                && plan.getProjections().equals(List.of("CODE", "NAME"))
                && plan.getQueryType() == QueryType.LIST)))
                .thenReturn(StructuredQueryResult.builder().rows(List.of(row)).rowCount(1).build());

        MultiFieldProjectionService service = new MultiFieldProjectionService(fields, metrics, entities, executor);
        MultiFieldProjectionService.Result result = service.tryHandle(
                "把编码和名称分别列出来", 8L, "PRODUCT", List.of());

        assertThat(result.state()).isEqualTo(MultiFieldProjectionService.State.ANSWER);
        assertThat(result.plan().getProjections()).containsExactly("CODE", "NAME");
        assertThat(result.answer()).contains("编码=P001").contains("名称=商品A");
    }

    @Test
    void oneFieldDoesNotHijackExistingSingleFieldPath() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        fields.register(FieldDefinition.builder().domainCode("PRODUCT").entityType("ITEM")
                .fieldCode("CODE").valueType("STRING").aliases(List.of("编码")).build());
        MultiFieldProjectionService service = new MultiFieldProjectionService(
                fields, new DefaultDomainMetricRegistry(), new DefaultDomainEntityRegistry(), executor);

        MultiFieldProjectionService.Result result = service.tryHandle("编码是什么", 8L, "PRODUCT", List.of());

        assertThat(result.state()).isEqualTo(MultiFieldProjectionService.State.NOT_APPLICABLE);
    }
}
