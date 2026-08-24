package cn.iocoder.yudao.module.evidence.service.planner;

import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.ExecutionMode;
import cn.iocoder.yudao.module.model.api.ModelApi;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Query Planner deterministic gate：100 个自然语言变体必须稳定归一到有限 QueryPlan。
 * 这些 Case 禁止调用 LLM；CI/Maven test 可离线执行。
 */
class QueryCapabilityGoldenMatrixTest {

    private final ModelApi modelApi = Mockito.mock(ModelApi.class);
    private final DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
    private final DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
    private final QueryPlanValidator validator = new QueryPlanValidator(fields, metrics);
    private final QueryPlannerV2 delegate = new QueryPlannerV2(
            new CompletenessGuard(), fields, metrics, modelApi, Mockito.mock(PromptSupport.class), validator);
    private final QueryPlannerFacade planner = new QueryPlannerFacade(delegate);

    @TestFactory
    Stream<DynamicTest> deterministicCapabilityMatrix() {
        List<Case> cases = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            cases.add(new Case("compare-" + i, "哪些专利比较相似？场景" + i,
                    QueryClass.SEMANTIC_QUERY, ExecutionMode.CROSS_ENTITY_COMPARE, List.of()));
            cases.add(new Case("exact-" + i, "原文中包含“粒子化磁涌" + i + "”吗？",
                    QueryClass.SEMANTIC_QUERY, ExecutionMode.EXACT_TEXT_SEARCH, List.of()));
            cases.add(new Case("subjective-" + i, "这几个专利哪个好？场景" + i,
                    QueryClass.CLARIFY, null, List.of()));
            cases.add(new Case("structured-" + i, "当前知识库有几个专利？场景" + i,
                    QueryClass.STRUCTURED_QUERY, ExecutionMode.STRUCTURED, List.of()));
            cases.add(new Case("per-entity-" + i, "这些专利的核心技术分别是什么？场景" + i,
                    QueryClass.SEMANTIC_QUERY, ExecutionMode.PER_ENTITY_SEMANTIC, List.of(65L, 66L, 67L)));
        }

        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            QueryPlan plan = planner.plan(c.query(), "PATENT", List.of(), c.entityIds(), null);
            assertThat(plan.getQueryClass()).isEqualTo(c.queryClass());
            assertThat(plan.getExecutionMode()).isEqualTo(c.executionMode());
            assertThat(plan.getPlannerSource()).isEqualTo("DETERMINISTIC");
            if (c.executionMode() == ExecutionMode.EXACT_TEXT_SEARCH) {
                assertThat(plan.getExactText()).isNotBlank();
            }
            if (c.executionMode() == ExecutionMode.CROSS_ENTITY_COMPARE) {
                assertThat(plan.getCoveragePolicy()).isEqualTo("ALL");
            }
        })).onClose(() -> verifyNoInteractions(modelApi));
    }

    private record Case(String name, String query, QueryClass queryClass,
                        ExecutionMode executionMode, List<Long> entityIds) {}
}
