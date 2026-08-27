package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredFilterArgumentValidatorTest {

    private StructuredQueryCapability capability;
    private CapabilityInvocationContext context;

    @BeforeEach
    void setUp() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        capability = new StructuredQueryCapability(fields, metrics, entities, null, null);
        context = new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-filter-validation");
    }

    @Test
    void rejectsMutuallyExclusiveEqualsOnSingleValueField() {
        CapabilityArgumentValidation validation = capability.validateArguments(context, Map.of(
                "filter", Map.of(
                        "logic", "AND",
                        "children", List.of(
                                Map.of("field", "APPLICATION_NO", "operator", "EQ", "values", List.of("A")),
                                Map.of("field", "APPLICATION_NO", "operator", "EQ", "values", List.of("B"))
                        )
                )
        ));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.message()).contains("contradictory filter")
                .contains("APPLICATION_NO").contains("IN or OR");
    }

    @Test
    void acceptsAlternativeValuesExpressedAsIn() {
        CapabilityArgumentValidation validation = capability.validateArguments(context, Map.of(
                "filter", Map.of(
                        "field", "APPLICATION_NO",
                        "operator", "IN",
                        "values", List.of("A", "B")
                )
        ));

        assertThat(validation.valid()).isTrue();
    }

    @Test
    void doesNotRejectConjunctiveMembershipOnMultiValueField() {
        CapabilityArgumentValidation validation = capability.validateArguments(context, Map.of(
                "filter", Map.of(
                        "logic", "AND",
                        "children", List.of(
                                Map.of("field", "INVENTOR", "operator", "EQ", "values", List.of("张三")),
                                Map.of("field", "INVENTOR", "operator", "EQ", "values", List.of("李四"))
                        )
                )
        ));

        assertThat(validation.valid()).isTrue();
    }

    @Test
    void havingMustBeAnObjectAtCapabilityBoundary() {
        CapabilityArgumentValidation validation = capability.validateArguments(context,
                Map.of("having", List.of(Map.of("operator", "GT", "values", List.of(1)))));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.message()).isEqualTo("having must be an object");
    }
}
