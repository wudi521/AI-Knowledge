package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatContextFrameMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatResultSetMapper;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.chat.service.context.model.RevalidationResult;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.DocumentVisibilityReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResultSetServiceTest {

    @Mock
    private AiChatResultSetMapper resultSetMapper;
    @Mock
    private AiChatContextFrameMapper frameMapper;
    @Mock
    private ChatProperties chatProperties;
    @Mock
    private ConversationQueryStateService queryStateService;
    @Mock
    private KnowledgeApi knowledgeApi;

    private ResultSetService service;

    @BeforeEach
    void setUp() {
        service = new ResultSetService();
        ReflectionTestUtils.setField(service, "resultSetMapper", resultSetMapper);
        ReflectionTestUtils.setField(service, "frameMapper", frameMapper);
        ReflectionTestUtils.setField(service, "chatProperties", chatProperties);
        ReflectionTestUtils.setField(service, "queryStateService", queryStateService);
        ReflectionTestUtils.setField(service, "knowledgeApi", knowledgeApi);
        when(chatProperties.getResultSetInlineThreshold()).thenReturn(200);
        when(chatProperties.getContextFrameLimit()).thenReturn(10);
    }

    @Test
    void createSmallResultSetStoresInlineIdsPreservingOrder() {
        ResultSetSnapshot snapshot = ResultSetSnapshot.builder()
                .resultSetId("rs-1").queryId("q-1").conversationId(100L)
                .entityType("PATENT_DOCUMENT")
                .orderedEntityIds(List.of(66L, 67L, 68L, 69L))
                .build();

        ResultSetSnapshot saved = service.createResultSet(snapshot);

        assertThat(saved.getStorageMode()).isEqualTo(ResultSetSnapshot.STORAGE_INLINE);
        assertThat(saved.getEntityCount()).isEqualTo(4);
        assertThat(saved.getOrderedEntityIds()).containsExactly(66L, 67L, 68L, 69L);
        ArgumentCaptor<cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO> captor =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO.class);
        verify(resultSetMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrderedEntityIds()).contains("66", "67", "68", "69");
    }

    @Test
    void createLargeResultSetSwitchesToRefWithoutIds() {
        when(chatProperties.getResultSetInlineThreshold()).thenReturn(2);
        ResultSetSnapshot snapshot = ResultSetSnapshot.builder()
                .resultSetId("rs-2").queryId("q-2").conversationId(100L)
                .entityType("PATENT_DOCUMENT")
                .orderedEntityIds(List.of(1L, 2L, 3L, 4L, 5L))
                .scopeDescriptor("{\"kbId\":6}")
                .build();

        ResultSetSnapshot saved = service.createResultSet(snapshot);

        assertThat(saved.getStorageMode()).isEqualTo(ResultSetSnapshot.STORAGE_REF);
        assertThat(saved.getOrderedEntityIds()).isNull();
        assertThat(saved.getEntityCount()).isEqualTo(5);
    }

    @Test
    void pushFrameIncrementsSeqAndTrimsOldFrames() {
        when(chatProperties.getContextFrameLimit()).thenReturn(3); // nextSeq=4 > 3 触发清理
        AiChatContextFrameDO latest = new AiChatContextFrameDO();
        latest.setSeq(3);
        when(frameMapper.selectRecentByConversationId(100L, 1)).thenReturn(List.of(latest));
        when(resultSetMapper.selectByResultSetId("rs-1"))
                .thenReturn(new cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO() {{
                    setResultSetId("rs-1");
                    setEntityCount(4);
                    setStorageMode(ResultSetSnapshot.STORAGE_INLINE);
                }});

        ContextFrame frame = ContextFrame.builder()
                .conversationId(100L).queryId("q-4").entityType("PATENT_DOCUMENT")
                .resultSetId("rs-1").metricCode("CLAIM_COUNT").fieldCode("PUBLICATION_NO")
                .operation("NONE").scopeType("PREVIOUS_RESULT_SET").queryType("LIST")
                .executionMode("STRUCTURED").queryText("它们的公布号")
                .build();

        ContextFrame pushed = service.pushFrame(frame);

        assertThat(pushed.getSeq()).isEqualTo(4);
        ArgumentCaptor<AiChatContextFrameDO> captor = ArgumentCaptor.forClass(AiChatContextFrameDO.class);
        verify(frameMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isEqualTo(4);
        verify(frameMapper).deleteOlderThan(eq(100L), anyInt());
        verify(queryStateService).updateQueryState(eq(100L), any());
    }

    @Test
    void materializeInlineReturnsIds() {
        ResultSetSnapshot snapshot = ResultSetSnapshot.builder()
                .storageMode(ResultSetSnapshot.STORAGE_INLINE)
                .orderedEntityIds(List.of(7L, 8L))
                .build();

        assertThat(service.materialize(snapshot)).containsExactly(7L, 8L);
    }

    @Test
    void markStaleUpdatesStatus() {
        cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO row =
                new cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO();
        row.setResultSetId("rs-1");
        row.setStatus(ResultSetSnapshot.STATUS_VALID);
        when(resultSetMapper.selectByResultSetId("rs-1")).thenReturn(row);

        service.markStale("rs-1");

        assertThat(row.getStatus()).isEqualTo(ResultSetSnapshot.STATUS_STALE);
        verify(resultSetMapper).updateById(row);
    }

    // ========== CQ-38 revalidate ==========

    private ResultSetSnapshot inlineRs(String resultSetId, Long kbId, String domainCode, List<Long> ids) {
        return ResultSetSnapshot.builder()
                .resultSetId(resultSetId).conversationId(100L)
                .kbId(kbId).domainCode(domainCode)
                .entityType("PATENT_DOCUMENT")
                .storageMode(ResultSetSnapshot.STORAGE_INLINE)
                .orderedEntityIds(ids).status(ResultSetSnapshot.STATUS_VALID)
                .build();
    }

    private void stubVisibility(Map<Long, String> visibility) {
        when(knowledgeApi.getDocumentVisibility(any(DocumentVisibilityReqDTO.class)))
                .thenReturn(CommonResult.success(visibility));
    }

    @Test
    void revalidateKbMismatch_returnsDomainMismatch() {
        when(resultSetMapper.selectByResultSetId("rs-1"))
                .thenReturn(inlineRs("rs-1", 6L, "PATENT", List.of(1L, 2L)).toDO());

        RevalidationResult r = service.revalidate("rs-1", 42L, 99L, "PATENT");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getReasonCode()).isEqualTo("DOMAIN_MISMATCH");
    }

    @Test
    void revalidateAllVisible_returnsValid() {
        when(resultSetMapper.selectByResultSetId("rs-1"))
                .thenReturn(inlineRs("rs-1", 6L, "PATENT", List.of(1L, 2L)).toDO());
        stubVisibility(Map.of(1L, "VISIBLE", 2L, "VISIBLE"));

        RevalidationResult r = service.revalidate("rs-1", 42L, 6L, "PATENT");

        assertThat(r.isValid()).isTrue();
        assertThat(r.isContextChanged()).isFalse();
    }

    @Test
    void revalidateAllPermissionChanged_returnsInvalidPermission() {
        when(resultSetMapper.selectByResultSetId("rs-1"))
                .thenReturn(inlineRs("rs-1", 6L, "PATENT", List.of(1L, 2L)).toDO());
        stubVisibility(Map.of(1L, "PERMISSION_CHANGED", 2L, "PERMISSION_CHANGED"));

        RevalidationResult r = service.revalidate("rs-1", 42L, 6L, "PATENT");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getReasonCode()).isEqualTo("PERMISSION_CHANGED");
        verify(resultSetMapper).updateById(any(cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO.class));
    }

    @Test
    void revalidatePartial_keepsRemainingAndRemoved() {
        when(resultSetMapper.selectByResultSetId("rs-1"))
                .thenReturn(inlineRs("rs-1", 6L, "PATENT", List.of(1L, 2L, 3L)).toDO());
        stubVisibility(Map.of(1L, "VISIBLE", 2L, "STALE_RESULT_SET", 3L, "VISIBLE"));

        RevalidationResult r = service.revalidate("rs-1", 42L, 6L, "PATENT");

        assertThat(r.isValid()).isFalse();
        assertThat(r.isContextChanged()).isTrue();
        assertThat(r.getRemainingIds()).containsExactly(1L, 3L);
        assertThat(r.getRemovedIds()).containsExactly(2L);
    }

    @Test
    void revalidateRefResultSet_skipsEntityCheck() {
        when(resultSetMapper.selectByResultSetId("rs-ref"))
                .thenReturn(ResultSetSnapshot.builder()
                        .resultSetId("rs-ref").conversationId(100L).kbId(6L).domainCode("PATENT")
                        .storageMode(ResultSetSnapshot.STORAGE_REF)
                        .orderedEntityIds(null).entityCount(300).status(ResultSetSnapshot.STATUS_VALID)
                        .build().toDO());

        RevalidationResult r = service.revalidate("rs-ref", 42L, 6L, "PATENT");

        assertThat(r.isValid()).isTrue();
    }

}
