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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0 回归：ResultSet 只服务真正的多轮引用，不得污染后续独立问题。
 */
@ExtendWith(MockitoExtension.class)
class ConversationResultSetRegressionTest {

    @Mock
    private ResultSetService resultSetService;
    @Mock
    private ResultSetRevalidationService resultSetRevalidationService;

    private ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolver();
        ReflectionTestUtils.setField(resolver, "resultSetService", resultSetService);
        ReflectionTestUtils.setField(resolver, "resultSetRevalidationService", resultSetRevalidationService);
        lenient().when(resultSetRevalidationService.revalidate(any(), any(), any(), any()))
                .thenReturn(RevalidationResult.valid());
        lenient().when(resultSetService.materialize(any(ResultSetSnapshot.class)))
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
        assertThat(resolution.getMetricCode()).isEqualTo("DOCUMENT_COUNT");
        assertThat(resolution.getOperation()).isEqualTo("COUNT");
    }

    @Test
    void unrelatedFollowUpDoesNotTouchPreviousResultSet() {
        ContextFrame frame = ContextFrame.builder()
                .conversationId(199L)
                .seq(1)
                .queryId("q-previous")
                .entityType("PATENT_DOCUMENT")
                .resultSetId("rs-previous")
                .metricCode("DOCUMENT_COUNT")
                .operation("COUNT")
                .queryType("AGGREGATE")
                .build();

        QueryContextResolution resolution = resolver.resolve(
                "你谁啊",
                List.of(frame),
                "PATENT_DOCUMENT",
                1L,
                6L,
                "PATENT");

        assertThat(resolution.isClarifyRequired()).isFalse();
        assertThat(resolution.getScopeType()).isEqualTo(QueryContextResolution.SCOPE_CURRENT_KB);
        assertThat(resolution.getResultSetId()).isNull();
        verifyNoInteractions(resultSetService, resultSetRevalidationService);
    }
}
