package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatContextFrameMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatResultSetMapper;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.QueryContextResolution;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.DocumentVisibilityReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CQ-50 长会话回归(20+ turn): 模拟 25 轮查询, 验证帧栈在 contextFrameLimit 下稳定截断、
 * 会话轻量查询状态持续更新、以及"它们/这些"引用在长历史中仍绑定最近匹配帧。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatLongTurnRegressionTest {

    private static final int TURNS = 25;
    private static final Long CONVERSATION_ID = 100L;

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

    private final List<AiChatContextFrameDO> frames = new ArrayList<>();
    private final Map<String, AiChatResultSetDO> resultSets = new HashMap<>();

    private ResultSetService resultSetService;
    private ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resultSetService = new ResultSetService();
        ReflectionTestUtils.setField(resultSetService, "resultSetMapper", resultSetMapper);
        ReflectionTestUtils.setField(resultSetService, "frameMapper", frameMapper);
        ReflectionTestUtils.setField(resultSetService, "chatProperties", chatProperties);
        ReflectionTestUtils.setField(resultSetService, "queryStateService", queryStateService);
        ReflectionTestUtils.setField(resultSetService, "knowledgeApi", knowledgeApi);
        resolver = new ReferenceResolver();
        ReflectionTestUtils.setField(resolver, "resultSetService", resultSetService);

        when(chatProperties.getContextFrameLimit()).thenReturn(10);
        when(chatProperties.getResultSetInlineThreshold()).thenReturn(200);

        // 帧栈模拟 DB: 按 seq 降序返回
        when(frameMapper.selectRecentByConversationId(eq(CONVERSATION_ID), anyInt())).thenAnswer(inv -> {
            int limit = inv.getArgument(1);
            return frames.stream().sorted(Comparator.comparing(AiChatContextFrameDO::getSeq).reversed())
                    .limit(limit).toList();
        });
        // 插入即入栈
        doAnswer(inv -> {
            frames.add(inv.getArgument(0));
            return 1;
        }).when(frameMapper).insert(any(AiChatContextFrameDO.class));
        // 结果集读取
        when(resultSetMapper.selectByResultSetId(any())).thenAnswer(inv ->
                resultSets.get(inv.getArgument(0)));
        // 多轮引用可见性: 全部 VISIBLE
        when(knowledgeApi.getDocumentVisibility(any(DocumentVisibilityReqDTO.class)))
                .thenAnswer(inv -> {
                    DocumentVisibilityReqDTO req = inv.getArgument(0);
                    Map<Long, String> m = new HashMap<>();
                    if (req.getDocumentIds() != null) {
                        for (Long id : req.getDocumentIds()) {
                            m.put(id, "VISIBLE");
                        }
                    }
                    return CommonResult.success(m);
                });
    }

    private void pushResultSet(int seq) {
        String queryId = "q-" + seq;
        String rsId = "rs-" + seq;
        AiChatResultSetDO row = new AiChatResultSetDO();
        row.setResultSetId(rsId);
        row.setQueryId(queryId);
        row.setConversationId(CONVERSATION_ID);
        row.setKbId(6L);
        row.setDomainCode("PATENT");
        row.setEntityType("PATENT_DOCUMENT");
        row.setEntityCount(1);
        row.setStorageMode(ResultSetSnapshot.STORAGE_INLINE);
        row.setOrderedEntityIds("[" + (1000 + seq) + "]");
        row.setStatus(ResultSetSnapshot.STATUS_VALID);
        resultSets.put(rsId, row);
        resultSetService.pushFrame(ContextFrame.builder()
                .conversationId(CONVERSATION_ID)
                .queryId(queryId)
                .entityType("PATENT_DOCUMENT")
                .resultSetId(rsId)
                .executionMode("STRUCTURED")
                .build());
    }

    @Test
    void twentyFiveTurns_framesTrimmedAndReferenceBoundToLatest() {
        // 25 轮: 每轮生成一个结果集 + 上下文帧
        for (int i = 1; i <= TURNS; i++) {
            pushResultSet(i);
        }

        // 帧限制 10 → 超过后每轮触发 deleteOlderThan(保留最近 10 帧)
        verify(frameMapper, org.mockito.Mockito.atLeastOnce()).deleteOlderThan(eq(CONVERSATION_ID), anyInt());
        // 会话查询状态每轮更新(不依赖帧窗口)
        verify(queryStateService, org.mockito.Mockito.atLeast(TURNS)).updateQueryState(eq(CONVERSATION_ID), any());

        // 引用"它们的公布号分别是什么" → 绑定最近匹配帧(rs-25)
        List<ContextFrame> recent = resultSetService.getRecentFrames(CONVERSATION_ID);
        assertThat(recent).isNotEmpty();
        QueryContextResolution r = resolver.resolve("它们的公布号分别是什么？",
                recent, "PATENT_DOCUMENT", 42L, 6L, "PATENT");
        assertThat(r.isClarifyRequired()).isFalse();
        assertThat(r.getResultSetId()).isEqualTo("rs-" + TURNS);
        assertThat(r.getExplicitEntityIds()).containsExactly(1000L + TURNS);
    }

    @Test
    void longHistory_referenceStaysStableAcrossManyTurns() {
        // 先铺 22 轮历史, 再引用中途某轮的结果集(通过显式 queryId 帧已不在最近窗口, 引用最近帧)
        for (int i = 1; i <= 22; i++) {
            pushResultSet(i);
        }
        // 第 23 轮绑定新的结果集并引用
        pushResultSet(23);
        List<ContextFrame> recent = resultSetService.getRecentFrames(CONVERSATION_ID);
        QueryContextResolution r = resolver.resolve("这些的核心技术分别是什么？",
                recent, "PATENT_DOCUMENT", 42L, 6L, "PATENT");
        // 最近帧即 rs-23(即使有 23 帧历史, 引用不漂移)
        assertThat(r.getResultSetId()).isEqualTo("rs-23");
        assertThat(r.getExplicitEntityIds()).containsExactly(1023L);
    }
}
