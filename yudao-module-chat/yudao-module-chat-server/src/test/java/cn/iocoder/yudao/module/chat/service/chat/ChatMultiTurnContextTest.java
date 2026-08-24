package cn.iocoder.yudao.module.chat.service.chat;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CQ-02/22/47 多轮上下文集成: 结构化结果 → ResultSetSnapshot + 上下文帧落库(供后续"这些/它们"继承)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatMultiTurnContextTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageService messageService;
    @Mock
    private EvidenceRpcAdapter evidenceRpcAdapter;
    @Mock
    private TransferHandler transferHandler;
    @Mock
    private ChatProperties chatProperties;
    @Mock
    private KnowledgeApi knowledgeApi;
    @Mock
    private cn.iocoder.yudao.module.chat.service.trace.QueryTraceService queryTraceService;
    @Mock
    private cn.iocoder.yudao.module.chat.service.context.ReferenceResolver referenceResolver;
    @Mock
    private cn.iocoder.yudao.module.chat.service.context.ResultSetService resultSetService;

    private ChatPipeline pipeline;
    private MockedStatic<SecurityFrameworkUtils> securityFrameworkUtils;

    @BeforeEach
    void setUp() {
        pipeline = new ChatPipeline();
        ReflectionTestUtils.setField(pipeline, "conversationService", conversationService);
        ReflectionTestUtils.setField(pipeline, "messageService", messageService);
        ReflectionTestUtils.setField(pipeline, "evidenceRpcAdapter", evidenceRpcAdapter);
        ReflectionTestUtils.setField(pipeline, "transferHandler", transferHandler);
        ReflectionTestUtils.setField(pipeline, "chatProperties", chatProperties);
        ReflectionTestUtils.setField(pipeline, "channelAdapters", List.of());
        ReflectionTestUtils.setField(pipeline, "knowledgeApi", knowledgeApi);
        ReflectionTestUtils.setField(pipeline, "queryTraceService", queryTraceService);
        ReflectionTestUtils.setField(pipeline, "referenceResolver", referenceResolver);
        ReflectionTestUtils.setField(pipeline, "resultSetService", resultSetService);
        when(queryTraceService.newTraceId()).thenReturn("q-test123456");

        LoginUser loginUser = new LoginUser();
        loginUser.setId(42L);
        loginUser.setTenantId(7L);
        securityFrameworkUtils = org.mockito.Mockito.mockStatic(SecurityFrameworkUtils.class);
        securityFrameworkUtils.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(6L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L))).thenReturn(CommonResult.success(Map.of(6L, "PATENT")));
        when(chatProperties.getMaxContextMessages()).thenReturn(6);
    }

    @AfterEach
    void tearDown() {
        securityFrameworkUtils.close();
    }

    @Test
    void structuredResultPersistsResultSetAndFrame() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        conversation.setKbId(6L);
        conversation.setUserId(42L);
        conversation.setStatus("ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);

        EvidenceEvaluateRespDTO resp = new EvidenceEvaluateRespDTO();
        resp.setAnswerable(true);
        resp.setAnswer("当前知识库共有 3 件已发布专利文献。");
        resp.setRoute("STRUCTURED_QUERY");
        resp.setQuery("有多少个专利？");
        StructuredResultDTO sr = new StructuredResultDTO();
        sr.setEntityIds(List.of(101L, 102L, 103L));
        sr.setEntityType("PATENT_DOCUMENT");
        sr.setMetricCode("DOCUMENT_COUNT");
        sr.setQueryType("LIST");
        sr.setScopeType("CURRENT_KB");
        resp.setStructuredResult(sr);
        when(evidenceRpcAdapter.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resp);
        when(referenceResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(cn.iocoder.yudao.module.chat.service.context.model.QueryContextResolution.noReference());
        when(resultSetService.getRecentFrames(100L)).thenReturn(List.of());
        when(resultSetService.existsByQueryId("q-test123456")).thenReturn(false);
        when(resultSetService.createResultSet(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO aiMessage = new AiMessageDO();
        aiMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, aiMessage);

        pipeline.send(100L, "有多少个专利？", "web", null, 6L);

        // 结构化结果 → 保序 ResultSetSnapshot
        ArgumentCaptor<cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot> rsCap =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot.class);
        verify(resultSetService).createResultSet(rsCap.capture());
        assertThat(rsCap.getValue().getOrderedEntityIds()).containsExactly(101L, 102L, 103L);
        assertThat(rsCap.getValue().getEntityType()).isEqualTo("PATENT_DOCUMENT");
        // 上下文帧(供后续"它们申请号呢"继承范围/字段)
        ArgumentCaptor<cn.iocoder.yudao.module.chat.service.context.model.ContextFrame> fCap =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.chat.service.context.model.ContextFrame.class);
        verify(resultSetService).pushFrame(fCap.capture());
        assertThat(fCap.getValue().getQueryId()).isEqualTo("q-test123456");
        assertThat(fCap.getValue().getScopeType()).isEqualTo("CURRENT_KB");
        assertThat(fCap.getValue().getExecutionMode()).isEqualTo("STRUCTURED");
    }

    @Test
    void semanticExecutionPersistsResultSetWithExecutionMode() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        conversation.setKbId(6L);
        conversation.setUserId(42L);
        conversation.setStatus("ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);

        // CQ-38: 语义执行(逐实体 SCOPED_RAG)返回 PER_ENTITY_SEMANTIC + 实体集回流
        EvidenceEvaluateRespDTO resp = new EvidenceEvaluateRespDTO();
        resp.setAnswerable(true);
        resp.setAnswer("专利A：核心技术X；专利B：核心技术Y");
        resp.setRoute("PER_ENTITY_SEMANTIC");
        resp.setExecutionMode("PER_ENTITY_SEMANTIC");
        resp.setQuery("它们的技术方案分别是什么？");
        StructuredResultDTO sr = new StructuredResultDTO();
        sr.setEntityIds(List.of(101L, 102L));
        sr.setEntityType("PATENT_DOCUMENT");
        sr.setQueryType("LIST");
        sr.setScopeType("DOCUMENT_SET");
        resp.setStructuredResult(sr);
        when(evidenceRpcAdapter.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resp);
        when(referenceResolver.resolve(any(), any(), any(), any(), any(), any()))
                .thenReturn(cn.iocoder.yudao.module.chat.service.context.model.QueryContextResolution.builder()
                        .scopeType("PREVIOUS_RESULT_SET")
                        .explicitEntityIds(List.of(101L, 102L))
                        .build());
        when(resultSetService.getRecentFrames(100L)).thenReturn(List.of());
        when(resultSetService.existsByQueryId("q-test123456")).thenReturn(false);
        when(resultSetService.createResultSet(any())).thenAnswer(inv -> inv.getArgument(0));
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3030L);
        AiMessageDO aiMessage = new AiMessageDO();
        aiMessage.setId(3031L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, aiMessage);

        pipeline.send(100L, "它们的技术方案分别是什么？", "web", null, 6L);

        ArgumentCaptor<cn.iocoder.yudao.module.chat.service.context.model.ContextFrame> fCap =
                ArgumentCaptor.forClass(cn.iocoder.yudao.module.chat.service.context.model.ContextFrame.class);
        verify(resultSetService).pushFrame(fCap.capture());
        assertThat(fCap.getValue().getQueryId()).isEqualTo("q-test123456");
        assertThat(fCap.getValue().getExecutionMode()).isEqualTo("PER_ENTITY_SEMANTIC");
        assertThat(fCap.getValue().getResultSetId()).isNotBlank();
    }

}
