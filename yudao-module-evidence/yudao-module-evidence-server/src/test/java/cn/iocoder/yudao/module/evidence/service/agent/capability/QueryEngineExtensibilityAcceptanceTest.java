package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.ElementBindingStructuredPipelineExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueEvaluator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构验收：不用 PATENT/Product Pack，不给 Core 增加任何 query intent 分支，
 * 只注册一个测试领域的数据合同后，公共 Query IR 仍可组合执行新问题。
 */
class QueryEngineExtensibilityAcceptanceTest {

    private static final String DOMAIN = "TEST_DOMAIN";
    private static final String ENTITY = "TEST_ITEM";
    private static final String CATEGORY = "CATEGORY";
    private static final String NAME = "NAME";

    @Test
    void unseenGroupAggregateHavingCompositionMustRunWithoutDomainSpecificHandler() {
        Fixture fixture = fixture();
        StructuredPipelineCapabilityDelegate delegate = fixture.delegate();

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("groupBy", List.of(CATEGORY));
        arguments.put("aggregate", Map.of("operation", "COUNT"));
        arguments.put("having", Map.of("operator", "GT", "values", List.of(1)));
        arguments.put("orderBy", List.of(Map.of("aggregateValue", true, "direction", "DESC")));
        arguments.put("limit", 20);

        CapabilityResult result = delegate.execute(context(), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("shape", "GROUP")
                .containsEntry("havingApplied", true)
                .containsEntry("outputCount", 1);
        StructuredPipelineCapabilityDelegate.Output output =
                (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.rowSummary()).contains("group=A").contains("value=2.0");
        assertThat(output.normalizedPlan()).contains("having=StructuredHavingSpec");
    }

    @Test
    void unseenTransformSortLimitCompositionMustReuseSameCore() {
        Fixture fixture = fixture();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("select", List.of(NAME));
        arguments.put("orderBy", List.of(Map.of(
                "field", NAME,
                "transforms", List.of("LENGTH"),
                "direction", "DESC")));
        arguments.put("limit", 1);

        CapabilityResult result = fixture.delegate().execute(context(), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.metadata()).containsEntry("shape", "ROWS")
                .containsEntry("outputCount", 1)
                .containsEntry("outputLimited", true);
        StructuredPipelineCapabilityDelegate.Output output =
                (StructuredPipelineCapabilityDelegate.Output) result.data();
        assertThat(output.rowSummary()).contains("much-longer-name");
    }

    @Test
    void newlyRegisteredCapabilityMustBeInvokableWithoutCoreSwitchCase() {
        KnowledgeCapability synthetic = new KnowledgeCapability() {
            private final CapabilityDefinition definition = new CapabilityDefinition(
                    "synthetic_echo", "1.0", "Synthetic test capability",
                    Map.of("value", "string"), Set.of("value"), "STRING_SCALAR", true,
                    Set.of(), Set.of(DOMAIN), Set.of(), 1000L, 10);

            @Override public CapabilityDefinition definition() { return definition; }

            @Override
            public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
                return CapabilityResult.success(arguments.get("value"), Map.of(
                        "resultShape", "SCALAR", "outputCount", 1));
            }
        };

        CapabilityRegistry registry = new CapabilityRegistry(List.of(synthetic), List.of());
        CapabilityInvoker invoker = new CapabilityInvoker(registry);
        try {
            assertThat(registry.listDefinitions(context())).extracting(CapabilityDefinition::name)
                    .containsExactly("synthetic_echo");
            CapabilityInvoker.PreparedCall call = invoker.prepare(
                    "synthetic_echo", Map.of("value", "works"), context());
            assertThat(call.accepted()).isTrue();
            CapabilityResult result = invoker.invoke(call, context());
            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo("works");
        } finally {
            invoker.shutdown();
        }
    }

    private Fixture fixture() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        fields.register(FieldDefinition.builder()
                .fieldCode(CATEGORY).domainCode(DOMAIN).entityType(ENTITY).valueType("STRING")
                .aliases(List.of("category"))
                .allowedOperators(Set.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN))
                .filterable(true).sortable(true).groupable(true)
                .build());
        fields.register(FieldDefinition.builder()
                .fieldCode(NAME).domainCode(DOMAIN).entityType(ENTITY).valueType("STRING")
                .aliases(List.of("name"))
                .allowedOperators(Set.of(FilterOperator.EQ, FilterOperator.CONTAINS))
                .filterable(true).sortable(true).groupable(true)
                .allowedTransforms(Set.of(StructuredValueTransform.LENGTH))
                .build());

        DomainStructuredDataAdapter adapter = new DomainStructuredDataAdapter() {
            @Override public String adapterKey() { return "TEST_DOMAIN_MEMORY"; }
            @Override public boolean supports(String code) { return CATEGORY.equals(code) || NAME.equals(code); }

            @Override
            public StructuredQueryResult execute(StructuredQueryPlan plan) {
                return StructuredQueryResult.builder()
                        .rows(List.of(
                                row(1L, "A", "short"),
                                row(2L, "A", "much-longer-name"),
                                row(3L, "B", "middle")))
                        .rowCount(3)
                        .truncated(false)
                        .build();
            }
        };

        StructuredValueEvaluator values = new StructuredValueEvaluator(fields);
        ElementBindingStructuredPipelineExecutor executor = new ElementBindingStructuredPipelineExecutor(
                fields, metrics, List.of(adapter), values);
        StructuredPipelineCapabilityDelegate delegate = new StructuredPipelineCapabilityDelegate(
                fields, metrics, entities, executor);
        return new Fixture(delegate);
    }

    private StructuredQueryResult.Row row(Long id, String category, String name) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CATEGORY, category);
        values.put(NAME, name);
        return StructuredQueryResult.Row.builder()
                .entityId(id)
                .entityKey("ITEM:" + id)
                .entityName(name)
                .fields(values)
                .build();
    }

    private CapabilityInvocationContext context() {
        return new CapabilityInvocationContext(1L, 2L, 3L, DOMAIN, "trace-test-domain",
                Set.of(), Set.of(), List.of(), "test", false);
    }

    private record Fixture(StructuredPipelineCapabilityDelegate delegate) {}
}
