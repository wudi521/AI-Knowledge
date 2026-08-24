package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.QueryContextResolution;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.chat.service.context.model.RevalidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P0 回归：上一轮结构化查询产生 4 个实体后，下一轮“4个...”必须绑定该 ResultSet，
 * 不能重新从自然语言 answer 中反推实体，更不能退化为 CURRENT_KB。
 */
@ExtendWith(MockitoExtension.class)
class ConversationResultSetRegressionTest {

    @Mock
    private ResultSetService resultSetService;

    private ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolver();
        ReflectionTestUtils.setField(resolver, "resultSetService", resultSetService);
        when(resultSetService.revalidate(any(), any(), any(), any())).thenReturn(RevalidationResult.valid());
        when(resultSetService.materialize(any(ResultSetSnapshot.class)))
                .thenAnswer(inv -> inv.<ResultSetSnapshot>getArgument(0).getOrderedEntityIds());
    }

    @Test
    void fourPatentFollowUpBindsPreviousResultSet() {
        List<Long> ids = List.of(65L, 66L, 67L, 68L);
        ResultSetSnapshot snapshot = ResultSetSnapshot.builder()
                .resultSetId("rs-four-patents")
                .queryId("q-count-four")
                .conversationId(195L)
                .kbId(6L)
                .domainCode("PATENT")
                .entityType("PATENT_DOCUMENT")
                .entityCount(4)
                .storageMode(ResultSetSnapshot.STORAGE_INLINE)
                .orderedEntityIds(ids)
                .status(ResultSetSnapshot.STATUS_VALID)
                .build();
        ContextFrame frame = ContextFrame.builder()
                .conversationId(195L)
                .seq(1)
                .queryId("q-count-four")
                .entityType("PATENT_DOCUMENT")
                .resultSetId("rs-four-patents")
                .metricCode("DOCUMENT_COUNT")
                .operation("COUNT")
                .queryType("AGGREGATE")
                .build();

        when(resultSetService.getResultSet("rs-four-patents")).thenReturn(snapshot);

        QueryContextResolution resolution = resolver.resolve(
                "把4个专利号分别给我一下",
                List.of(frame),
                "PATENT_DOCUMENT",
                1L,
                6L,
                "PATENT");

        assertThat(resolution.isClarifyRequired()).isFalse();
        assertThat(resolution.getScopeType())
                .isEqualTo(QueryContextResolution.SCOPE_PREVIOUS_RESULT_SET);
        assertThat(resolution.getResultSetId()).isEqualTo("rs-four-patents");
        assertThat(resolution.getExplicitEntityIds()).containsExactlyElementsOf(ids);
    }
}
