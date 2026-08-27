package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.iocoder.yudao.module.evidence.service.agent.AgentExecutionBudget;
import cn.iocoder.yudao.module.evidence.service.agent.capability.AgentCapabilityOutput;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityArgumentValidation;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityDefinition;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvoker;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityRegistry;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityResultStatus;
import cn.iocoder.yudao.module.evidence.service.agent.capability.KnowledgeCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture-level regression for typed multi-node dataflow.
 * The examples are deliberately domain-neutral: no patent/inventor special case belongs in Runtime.
 */
class AgentTypedDataflowTest {

    @Test
    void structuredResultPublishesStableMachineRowsInsteadOfOnlyDisplaySummary() {
        StructuredPipelineResult result = new StructuredPipelineResult(true, null, List.of(
                new StructuredPipelineResult.Row(11L, "row-a", Map.of("APPLICATION_NO", "P-1"), 4D, "P-1"),
                new StructuredPipelineResult.Row(12L, "row-b", Map.of("APPLICATION_NO", "P-2"), 2D, "P-2")
        ), null, true, false, 2, 0, Map.of());

        Object raw = result.metadata().get(StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY);
        assertTrue(raw instanceof List<?>);
        List<?> rows = (List<?>) raw;
        assertEquals(2, rows.size());
        assertEquals("P-1", ((Map<?, ?>) rows.get(0)).get("groupKey"));
        assertEquals(Map.of("APPLICATION_NO", "P-1"), ((Map<?, ?>) rows.get(0)).get("fields"));
    }

    @Test
    void runtimeCanProjectAndCarryValuesAcrossFourDependentNodes() {
        SourceRowsCapability source = new SourceRowsCapability("source", List.of("A", "A", "B"));
        FlowRowsCapability step2 = new FlowRowsCapability("step-2", "-2");
        FlowRowsCapability step3 = new FlowRowsCapability("step-3", "-3");
        FlowRowsCapability step4 = new FlowRowsCapability("step-4", "-4");
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(
                List.of(source, step2, step3, step4), List.of()));
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan("typed-flow-4", "四跳数据流", 0, List.of(
                    new PlanNode("n1", "source", Map.of(), "产生分组键", Set.of()),
                    new PlanNode("n2", "step-2", Map.of(
                            "values", Map.of(
                                    "$ref", "n1",
                                    "selector", "metadata",
                                    "path", "dataflowRows[*].groupKey",
                                    "distinct", true,
                                    "required", true,
                                    "expect", "LIST"
                            )), "消费上游分组键", Set.of("n1")),
                    new PlanNode("n3", "step-3", Map.of(
                            "values", Map.of(
                                    "$ref", "n2",
                                    "selector", "metadata",
                                    "path", "dataflowRows[*].fields.VALUE",
                                    "required", true,
                                    "expect", "LIST"
                            )), "消费第二跳字段", Set.of("n2")),
                    new PlanNode("n4", "step-4", Map.of(
                            "values", Map.of(
                                    "$ref", "n3",
                                    "selector", "metadata",
                                    "path", "dataflowRows[*].fields.VALUE",
                                    "required", true,
                                    "expect", "LIST"
                            )), "消费第三跳字段", Set.of("n3"))
            ));

            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-typed-flow"),
                    new AgentExecutionBudget(8, 8, 5_000L));

            assertEquals(CapabilityResultStatus.SUCCESS, result.status());
            FlowOutput output = (FlowOutput) result.nodeResults().get("n4").data();
            assertEquals(List.of("A-2-3-4", "B-2-3-4"), output.values());
            assertEquals(4, result.activities().size());
        } finally {
            invoker.shutdown();
        }
    }

    @Test
    void invalidReferencePathIsRejectedAtPlanValidation() {
        AgentExecutionPlan plan = new AgentExecutionPlan("invalid-ref", "非法引用", 0, List.of(
                new PlanNode("n1", "source", Map.of(), "source", Set.of()),
                new PlanNode("n2", "sink", Map.of(
                        "values", Map.of("$ref", "n1", "selector", "metadata",
                                "path", "dataflowRows[0].groupKey")
                ), "sink", Set.of("n1"))
        ));
        AgentExecutionPlanValidator.Validation validation = new AgentExecutionPlanValidator()
                .validate(plan, new AgentExecutionBudget(4, 4, 5_000L));

        assertFalse(validation.valid());
        assertTrue(validation.message().contains("invalid plan reference path"));
    }

    @Test
    void staticCapabilityShapeErrorIsRejectedBeforeAnyNodeExecutes() {
        FlowRowsCapability capability = new FlowRowsCapability("list-only", "");
        CapabilityInvoker invoker = new CapabilityInvoker(new CapabilityRegistry(List.of(capability), List.of()));
        try {
            AgentExecutionPlan plan = new AgentExecutionPlan("static-contract", "静态参数前置校验", 0, List.of(
                    new PlanNode("n1", "list-only", Map.of("values", "not-a-list"), "invalid", Set.of())
            ));
            AgentRuntimeResult result = new AgentRuntimeExecutor(invoker).execute(plan,
                    new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-static-contract"),
                    new AgentExecutionBudget(4, 4, 5_000L));

            assertEquals(CapabilityResultStatus.FAILED, result.status());
            assertTrue(result.activities().isEmpty(), "invalid static call must fail before node execution");
            assertTrue(result.message().contains("values must be a list"));
        } finally {
            invoker.shutdown();
        }
    }

    private static final class SourceRowsCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition;
        private final List<String> values;

        private SourceRowsCapability(String name, List<String> values) {
            this.definition = new CapabilityDefinition(name, "1", "测试数据流源",
                    Map.of(), Set.of(), "STRUCTURED_RESULT", true,
                    Set.of(), Set.of(), Set.of(), 1_000L, 50);
            this.values = List.copyOf(values);
        }

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            return CapabilityResult.success(new FlowOutput(values), Map.of(
                    StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY, groupRows(values),
                    "outputCount", values.size(),
                    "outputComplete", true));
        }
    }

    private static final class FlowRowsCapability implements KnowledgeCapability {
        private final CapabilityDefinition definition;
        private final String suffix;

        private FlowRowsCapability(String name, String suffix) {
            this.definition = new CapabilityDefinition(name, "1", "测试列表数据流变换",
                    Map.of("values", "字符串列表"), Set.of("values"), "STRUCTURED_RESULT", true,
                    Set.of(), Set.of(), Set.of(), 1_000L, 50);
            this.suffix = suffix;
        }

        @Override public CapabilityDefinition definition() { return definition; }

        @Override
        public CapabilityArgumentValidation validateArguments(CapabilityInvocationContext context,
                                                               Map<String, Object> arguments) {
            Object values = arguments == null ? null : arguments.get("values");
            if (!(values instanceof Iterable<?>)) {
                return CapabilityArgumentValidation.invalid("values must be a list");
            }
            return CapabilityArgumentValidation.ok();
        }

        @Override
        public CapabilityResult execute(CapabilityInvocationContext context, Map<String, Object> arguments) {
            List<String> next = new ArrayList<>();
            for (Object item : (Iterable<?>) arguments.get("values")) {
                if (item != null) next.add(String.valueOf(item) + suffix);
            }
            return CapabilityResult.success(new FlowOutput(next), Map.of(
                    StructuredPipelineResult.DATAFLOW_ROWS_METADATA_KEY, fieldRows(next),
                    "outputCount", next.size(),
                    "outputComplete", true));
        }
    }

    private record FlowOutput(List<String> values) implements AgentCapabilityOutput {
        private FlowOutput {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override public String summary() { return "values=" + values; }
        @Override public String progressHash() { return String.valueOf(values.hashCode()); }
        @Override public String deterministicAnswer() { return values.toString(); }
    }

    private static List<Map<String, Object>> groupRows(List<String> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityId", null);
            row.put("entityName", null);
            row.put("fields", Map.of());
            row.put("value", 1D);
            row.put("groupKey", value);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> fieldRows(List<String> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityId", null);
            row.put("entityName", null);
            row.put("fields", Map.of("VALUE", value));
            row.put("value", null);
            row.put("groupKey", null);
            rows.add(row);
        }
        return rows;
    }
}
