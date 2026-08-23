package cn.iocoder.yudao.module.chat.service.chat;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import cn.iocoder.yudao.module.chat.enums.chat.ChatRouteEnum;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceAnalysisDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceSlotValueDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatPipelineTest {

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
        ReflectionTestUtils.setField(pipeline, "channelAdapters", List.<ChannelAdapter>of());
        ReflectionTestUtils.setField(pipeline, "knowledgeApi", knowledgeApi);

        LoginUser loginUser = new LoginUser();
        loginUser.setId(42L);
        loginUser.setTenantId(7L);
        securityFrameworkUtils = org.mockito.Mockito.mockStatic(SecurityFrameworkUtils.class);
        securityFrameworkUtils.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
    }

    @AfterEach
    void tearDown() {
        securityFrameworkUtils.close();
    }

    @Test
    void newConversationUsesAuthorizedSingleKbContextWithoutBinding() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(6L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L))).thenReturn(CommonResult.success(Map.of(6L, "PATENT")));
        when(conversationService.createConversation("WEB", null, 6L, "PATENT", 42L)).thenReturn(conversation);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(null);

        pipeline.send(null, "专利权利要求1是什么？", "web", null, 6L);

        verify(conversationService).createConversation("WEB", null, 6L, "PATENT", 42L);
        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L)));
        verify(conversationService, never()).bindKbIds(any(), anyList());
    }

    @Test
    void answerResultReturnsStableMessageAndConversationContext() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        EvidenceAnalysisDTO analysis = new EvidenceAnalysisDTO();
        analysis.setIntent("PATENT_QA");
        response.setAnalysis(analysis);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, 7L);

        assertThat(result.getMessageId()).isEqualTo(3021L);
        assertThat(result.getConversationId()).isEqualTo(100L);
        assertThat(result.getKbId()).isEqualTo(6L);
        assertThat(result.getDomainCode()).isEqualTo("PATENT");
        assertThat(result.getIntent()).isEqualTo("PATENT_QA");
        assertThat(result.getDegraded()).isFalse();
    }

    @Test
    void clarifyResultReturnsStableAiMessageId() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setMissingSlots(List.of(new EvidenceSlotValueDTO()));
        response.setClarifyQuestion("请补充申请号");
        EvidenceAnalysisDTO analysis = new EvidenceAnalysisDTO();
        analysis.setIntent("PATENT_CLARIFY");
        response.setAnalysis(analysis);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO clarifyMessage = new AiMessageDO();
        clarifyMessage.setId(3022L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, clarifyMessage);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, 7L);

        assertThat(result.getMessageId()).isEqualTo(3022L);
        assertThat(result.getConversationId()).isEqualTo(100L);
        assertThat(result.getKbId()).isEqualTo(6L);
        assertThat(result.getDomainCode()).isEqualTo("PATENT");
        assertThat(result.getIntent()).isEqualTo("PATENT_CLARIFY");
        assertThat(result.getDegraded()).isFalse();
    }

    @Test
    void transferResultStillReturnsWithoutFinalMessageId() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(false);
        response.setRefusalReason("证据不足");
        EvidenceAnalysisDTO analysis = new EvidenceAnalysisDTO();
        analysis.setIntent("PATENT_OUT_OF_SCOPE");
        response.setAnalysis(analysis);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        ChatSendResult transferResult = ChatSendResult.builder()
                .conversationId(100L)
                .transferRequired(true)
                .transferReason("证据不足")
                .build();
        when(transferHandler.handleTransfer(eq(100L), eq("问题"), any(ChatSendResult.class)))
                .thenReturn(transferResult);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, 7L);

        assertThat(result).isSameAs(transferResult);
        assertThat(result.getTransferRequired()).isTrue();
        assertThat(result.getMessageId()).isNull();
        ArgumentCaptor<ChatSendResult> decisionCaptor = ArgumentCaptor.forClass(ChatSendResult.class);
        verify(transferHandler).handleTransfer(eq(100L), eq("问题"), decisionCaptor.capture());
        ChatSendResult decision = decisionCaptor.getValue();
        assertThat(decision.getTransferReason()).isEqualTo("证据不足");
        assertThat(decision.getDegraded()).isFalse();
        assertThat(decision.getKbId()).isEqualTo(6L);
        assertThat(decision.getIntent()).isEqualTo("PATENT_OUT_OF_SCOPE");
    }

    @Test
    void newConversationWithoutKbRejectsAsKnowledgeBaseNotExists() {
        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, null))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), anyString(), any(), anyString(), any());
        verify(knowledgeApi, never()).getVisibleKbIds(any());
    }

    @Test
    void newConversationWithInvisibleKbRejectsAsKnowledgeBaseNotExists() {
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(7L)));

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), anyString(), any(), anyString(), any());
        verify(knowledgeApi, never()).getKbDomainCodes(anyList());
    }

    @Test
    void newConversationWhenVisibleKbApiReturnsNullFailsClosed() {
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(null);

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), isNull(), any(), anyString(), any());
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void newConversationWhenVisibleKbApiReturnsErrorFailsClosed() {
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.error(1_003_099_999, "权限服务失败"));

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), isNull(), any(), anyString(), any());
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void newConversationWhenVisibleKbApiReturnsNullDataFailsClosed() {
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(null));

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), isNull(), any(), anyString(), any());
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void newConversationWithoutLoginUserFailsClosed() {
        securityFrameworkUtils.when(SecurityFrameworkUtils::getLoginUser).thenReturn(null);

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(conversationService, never()).createConversation(anyString(), isNull(), any(), anyString(), any());
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void existingConversationAlwaysUsesPersistedKbAndNeverBindsRequestKb() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, 7L);

        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L)));
        verify(conversationService, never()).bindKbIds(any(), anyList());
        verify(knowledgeApi, never()).getVisibleKbIds(any());
    }

    @Test
    void existingConversationForAnotherUserIsConversationNotExists() {
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> pipeline.send(100L, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_NOT_EXISTS.getCode());

        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void legacyConversationUsesFirstPositiveVisibleKbId() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("0,5,7");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(5L)));
        when(knowledgeApi.getKbDomainCodes(List.of(5L))).thenReturn(CommonResult.success(Map.of(5L, "")));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, null);

        verify(knowledgeApi).getVisibleKbIds(42L);
        verify(knowledgeApi).getKbDomainCodes(List.of(5L));
        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L)));
        verify(conversationService, never()).bindKbIds(any(), anyList());
    }

    @Test
    void legacyConversationAnswerUsesResolvedKnowledgeContextInResult() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("0,5,7");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(5L)));
        when(knowledgeApi.getKbDomainCodes(List.of(5L))).thenReturn(CommonResult.success(Map.of(5L, "PATENT")));
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("legacy answer");
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, null);

        assertThat(result.getKbId()).isEqualTo(5L);
        assertThat(result.getDomainCode()).isEqualTo("PATENT");
    }

    @Test
    void unavailableEvaluationTransferIsMarkedDegraded() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(null);
        ChatSendResult transferResult = ChatSendResult.builder()
                .conversationId(100L).transferRequired(true).build();
        when(transferHandler.handleTransfer(eq(100L), eq("问题"), any(ChatSendResult.class)))
                .thenReturn(transferResult);

        pipeline.send(100L, "问题", "web", null, 7L);

        ArgumentCaptor<ChatSendResult> decisionCaptor = ArgumentCaptor.forClass(ChatSendResult.class);
        verify(transferHandler).handleTransfer(eq(100L), eq("问题"), decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().getDegraded()).isTrue();
    }

    @Test
    void manualTransferDecisionIsNotDegraded() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(transferHandler.detectTransferReason("转人工")).thenReturn("客户要求人工");
        when(transferHandler.buildSummary(any(), any(), any(), any())).thenReturn("summary");
        ChatSendResult transferResult = ChatSendResult.builder()
                .conversationId(100L).transferRequired(true).build();
        when(transferHandler.handleTransfer(eq(100L), eq("转人工"), any(ChatSendResult.class)))
                .thenReturn(transferResult);

        pipeline.send(100L, "转人工", "web", null, 7L);

        ArgumentCaptor<ChatSendResult> decisionCaptor = ArgumentCaptor.forClass(ChatSendResult.class);
        verify(transferHandler).handleTransfer(eq(100L), eq("转人工"), decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().getDegraded()).isFalse();
    }

    @Test
    void closedConversationReturnsClosedResultWithoutKnowledgeOrEvidence() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "CLOSED");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, 7L);

        assertThat(result.getConversationId()).isEqualTo(100L);
        assertThat(result.getTransferRequired()).isTrue();
        assertThat(result.getTransferReason()).isEqualTo(TransferHandler.REASON_CLOSED);
        verify(knowledgeApi, never()).getVisibleKbIds(any());
        verify(knowledgeApi, never()).getKbDomainCodes(anyList());
        verify(messageService, never()).getRecentMessages(any(), anyInt());
        verify(messageService, never()).addMessage(any(), any(), any(), any(), any(), any(), any(), any());
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void legacyConversationSkipsInvalidKbIdBeforeUsingNextPositiveId() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("not-a-number,5");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(5L)));
        when(knowledgeApi.getKbDomainCodes(List.of(5L))).thenReturn(CommonResult.success(Map.of(5L, "PATENT")));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, null);

        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L)));
    }

    @Test
    void legacyConversationSkipsOverflowKbIdBeforeUsingNextPositiveId() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("9223372036854775808,5");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(5L)));
        when(knowledgeApi.getKbDomainCodes(List.of(5L))).thenReturn(CommonResult.success(Map.of(5L, "PATENT")));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, null);

        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(5L)));
    }

    @Test
    void legacyConversationWithOnlyInvalidKbIdsStopsBeforeEvidence() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("not-a-number,9223372036854775808,-1,0");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, null);

        assertThat(result.getConversationId()).isEqualTo(100L);
        assertThat(result.getAnswerable()).isFalse();
        assertThat(result.getDegraded()).isFalse();
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
        verify(knowledgeApi, never()).getVisibleKbIds(any());
    }

    @Test
    void answerResultRoutesScopedRagWhenEvidenceConfinedToOneDocument() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        EvidenceItemDTO item = new EvidenceItemDTO();
        item.setChunkId(2091L);
        item.setChunkMetadata("{\"applicationNo\":\"202311042981.1\",\"sectionType\":\"CLAIM\"}");
        response.setEvidence(List.of(item));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "申请号 202311042981.1 的核心技术方案是什么?", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.SCOPED_RAG);
    }

    @Test
    void answerResultRoutesHybridRagWhenEvidenceSpansDocuments() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        response.setEvidence(List.of(
                evidenceItem(2091L, "{\"applicationNo\":\"202311042981.1\",\"sectionType\":\"CLAIM\"}"),
                evidenceItem(2092L, "{\"applicationNo\":\"202311832214.0\",\"sectionType\":\"SUMMARY\"}")));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "哪一份专利使用电脑绣代替印花?", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.HYBRID_RAG);
    }

    @Test
    void transferDecisionRoutesAbstainWhenUnanswerable() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(false);
        response.setRefusalReason("证据不足");
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        ChatSendResult transferResult = ChatSendResult.builder()
                .conversationId(100L)
                .transferRequired(true)
                .transferReason("证据不足")
                .build();
        when(transferHandler.handleTransfer(eq(100L), eq("问题"), any(ChatSendResult.class)))
                .thenReturn(transferResult);

        pipeline.send(100L, "问题", "web", null, 7L);

        ArgumentCaptor<ChatSendResult> decisionCaptor = ArgumentCaptor.forClass(ChatSendResult.class);
        verify(transferHandler).handleTransfer(eq(100L), eq("问题"), decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().getRoute()).isEqualTo(ChatRouteEnum.ABSTAIN);
    }

    private AiConversationDO conversation(Long id, Long kbId, Long userId, String status) {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(id);
        conversation.setKbId(kbId);
        conversation.setUserId(userId);
        conversation.setStatus(status);
        return conversation;
    }

    private EvidenceItemDTO evidenceItem(Long chunkId, String chunkMetadata) {
        EvidenceItemDTO item = new EvidenceItemDTO();
        item.setChunkId(chunkId);
        item.setChunkMetadata(chunkMetadata);
        return item;
    }

}
