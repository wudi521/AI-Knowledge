package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredQueryCapabilityNormalizationTest {

    @Test
    void normalizesSingletonAggregateAndObjectTransformBeforeValidationAndExecution() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainEntityRegistry entities = mock(DomainEntityRegistry.class);
        StructuredPipelineCapabilityDelegate delegate = mock(StructuredPipelineCapabilityDelegate.class);
        StructuredQueryCapability capability = new StructuredQueryCapability(
                fields, metrics, entities, null, null, delegate);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace-normalize");

        when(delegate.canonicalPlanKey(eq("PATENT"), anyMap())).thenReturn("normalized-plan");
        when(delegate.execute(eq(context), anyMap())).thenReturn(CapabilityResult.success(
                Map.of("ok", true), Map.of(
                        "completeDataset", true,
                        "outputComplete", false,
                        "limited", true,
                        "missingValueCount", 0
                )));

        Map<String, Object> plannerArguments = Map.of(
                "groupBy", "TITLE",
                "aggregate", List.of(Map.of(
                        "operation", "COUNT_DISTINCT",
                        "field", "INVENTOR",
                        "explode", true,
                        "transforms", List.of(Map.of("operation", "PERSON_SURNAME"))
                )),
                "orderBy", Map.of("aggregateValue", true, "direction", "DESC"),
                "limit", 1
        );

        CapabilityArgumentValidation validation = capability.validateArguments(context, plannerArguments);
        CapabilityResult result = capability.execute(context, plannerArguments);

        assertThat(validation.valid()).isTrue();
        assertThat(result.metadata().get("rankedSelectionComplete")).isEqualTo(true);
        verify(delegate).execute(eq(context), org.mockito.ArgumentMatchers.argThat(arguments -> {
            Object aggregateRaw = arguments.get("aggregate");
            if (!(aggregateRaw instanceof Map<?, ?> aggregate)) return false;
            Object transformsRaw = aggregate.get("transforms");
            return transformsRaw instanceof List<?> transforms
                    && transforms.equals(List.of("PERSON_SURNAME"));
        }));
    }

    @Test
    void rejectsStaticPipelineThatCannotCompile() {
        DomainFieldRegistry fields = mock(DomainFieldRegistry.class);
        DomainMetricRegistry metrics = mock(DomainMetricRegistry.class);
        DomainEntityRegistry entities = mock(DomainEntityRegistry.class);
        StructuredPipelineCapabilityDelegate delegate = mock(StructuredPipelineCapabilityDelegate.class);
        StructuredQueryCapability capability = new StructuredQueryCapability(
                fields, metrics, entities, null, null, delegate);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                1L, 2L, 6L, "PATENT", "trace-invalid-static");

        when(delegate.canonicalPlanKey(eq("PATENT"), anyMap())).thenReturn(null);

        CapabilityArgumentValidation validation = capability.validateArguments(context, Map.of(
                "aggregate", Map.of("operation", "COUNT")
        ));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.message()).contains("cannot be compiled");
    }
}
