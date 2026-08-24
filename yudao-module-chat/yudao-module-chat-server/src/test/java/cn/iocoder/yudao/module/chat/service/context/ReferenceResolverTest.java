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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferenceResolverTest {

    @Mock
    private ResultSetService resultSetService;

    private ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolver();
        ReflectionTestUtils.setField(resolver, "resultSetService", resultSetService);
        // materialize 直接用快照内联 ids(测试不依赖真实物化)
        when(resultSetService.materialize(any(ResultSetSnapshot.class))).thenAnswer(inv ->
                inv.<ResultSetSnapshot>getArgument(0).getOrderedEntityIds());
        // 默认重校验通过(3 参 resolve 无用户上下文场景)
        when(resultSetService.revalidate(any(), any(), any(), any())).thenReturn(RevalidationResult.valid());
    }

    private ResultSetSnapshot rs(String id, String entityType, List<Long> ids) {
        return ResultSetSnapshot.builder()
                .resultSetId(id).entityType(entityType).entityCount(ids.size())
                .storageMode(ResultSetSnapshot.STORAGE_INLINE)
                .orderedEntityIds(ids).status(ResultSetSnapshot.STATUS_VALID)
                .build();
    }

    private ContextFrame frame(String resultSetId, String entityType, String metric, String field) {
        return ContextFrame.builder().conversationId(100L).seq(1).resultSetId(resultSetId)
                .entityType(entityType).metricCode(metric).fieldCode(field).build();
    }

    @Test
    void explicitEntityOverridesHistory() {
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(1L, 2L)));

        QueryContextResolution r = resolver.resolve("申请号 202311042981.1 的公布号？", frames, "PATENT_DOCUMENT");

        assertThat(r.getScopeType()).isEqualTo(QueryContextResolution.SCOPE_EXPLICIT_ENTITY);
        assertThat(r.isClarifyRequired()).isFalse();
    }

    @Test
    void noReferenceYieldsCurrentKb() {
        QueryContextResolution r = resolver.resolve("当前知识库有多少个专利？", List.of(), null);
        assertThat(r.getScopeType()).isEqualTo(QueryContextResolution.SCOPE_CURRENT_KB);
    }

    @Test
    void pronounReferencesLatestResultSet() {
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, "PUBLICATION_NO"));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(10L, 20L, 30L, 40L)));

        QueryContextResolution r = resolver.resolve("它们的公布号分别是什么？", frames, "PATENT_DOCUMENT");

        assertThat(r.getScopeType()).isEqualTo(QueryContextResolution.SCOPE_PREVIOUS_RESULT_SET);
        assertThat(r.getResultSetId()).isEqualTo("rs-1");
        assertThat(r.getExplicitEntityIds()).containsExactly(10L, 20L, 30L, 40L);
    }

    @Test
    void ordinalSubsetSelectsEntity() {
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(10L, 20L, 30L, 40L)));

        QueryContextResolution r = resolver.resolve("第二个的申请人是谁？", frames, "PATENT_DOCUMENT");

        assertThat(r.getExplicitEntityIds()).containsExactly(20L);
    }

    @Test
    void cardinalityMismatchClarifies() {
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(10L, 20L, 30L, 40L)));

        QueryContextResolution r = resolver.resolve("这三个专利的技术方案是什么？", frames, "PATENT_DOCUMENT");

        assertThat(r.isClarifyRequired()).isTrue();
        assertThat(r.getReasonCode()).isEqualTo("AMBIGUOUS_SCOPE");
    }

    @Test
    void staleResultSetClarifies() {
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        ResultSetSnapshot stale = rs("rs-1", "PATENT_DOCUMENT", List.of(1L, 2L));
        stale.setStatus(ResultSetSnapshot.STATUS_STALE);
        when(resultSetService.getResultSet("rs-1")).thenReturn(stale);

        QueryContextResolution r = resolver.resolve("这些呢？", frames, "PATENT_DOCUMENT");

        assertThat(r.isClarifyRequired()).isTrue();
        assertThat(r.getReasonCode()).isEqualTo("STALE_RESULT_SET");
    }

    @Test
    void recencyPrefersMatchingEntityType() {
        // Q3 applicant 帧(最近, seq=3) + Q2 patent 帧(seq=2): "这些专利"应绑定 PATENT_DOCUMENT 帧
        ContextFrame q3 = frame("rs-applicant", "APPLICANT", null, null);
        q3.setSeq(3);
        ContextFrame q2 = frame("rs-patent", "PATENT_DOCUMENT", "CLAIM_COUNT", null);
        q2.setSeq(2);
        when(resultSetService.getResultSet("rs-applicant")).thenReturn(rs("rs-applicant", "APPLICANT", List.of(5L)));
        when(resultSetService.getResultSet("rs-patent")).thenReturn(rs("rs-patent", "PATENT_DOCUMENT", List.of(1L, 2L, 3L)));

        QueryContextResolution r = resolver.resolve("这些专利的核心技术分别是什么？",
                List.of(q3, q2), "PATENT_DOCUMENT");

        assertThat(r.getResultSetId()).isEqualTo("rs-patent");
        assertThat(r.getExplicitEntityIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void revalidatePermissionChanged_clarifies() {
        // CQ-38: 引用结果集被整体判为 PERMISSION_CHANGED → 反问(不继续执行)
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(1L, 2L)));
        when(resultSetService.revalidate("rs-1", 42L, 6L, "PATENT"))
                .thenReturn(RevalidationResult.invalid("PERMISSION_CHANGED"));

        QueryContextResolution r = resolver.resolve("它们的公布号分别是什么？",
                frames, "PATENT_DOCUMENT", 42L, 6L, "PATENT");

        assertThat(r.isClarifyRequired()).isTrue();
        assertThat(r.getReasonCode()).isEqualTo("PERMISSION_CHANGED");
        assertThat(r.getClarifyQuestion()).contains("权限已变化");
    }

    @Test
    void revalidatePartial_usesRemainingIdsAndMarksContextChanged() {
        // CQ-38: 部分失效 → 剔除不可见实体, 引用剩余集并标记 contextChanged
        List<ContextFrame> frames = List.of(frame("rs-1", "PATENT_DOCUMENT", null, null));
        when(resultSetService.getResultSet("rs-1")).thenReturn(rs("rs-1", "PATENT_DOCUMENT", List.of(1L, 2L, 3L)));
        when(resultSetService.revalidate("rs-1", 42L, 6L, "PATENT"))
                .thenReturn(RevalidationResult.partial(List.of(1L, 3L), List.of(2L)));

        QueryContextResolution r = resolver.resolve("它们分别是什么？",
                frames, "PATENT_DOCUMENT", 42L, 6L, "PATENT");

        assertThat(r.isClarifyRequired()).isFalse();
        assertThat(r.getExplicitEntityIds()).containsExactly(1L, 3L);
        assertThat(Boolean.TRUE.equals(r.getContextChanged())).isTrue();
    }

}
