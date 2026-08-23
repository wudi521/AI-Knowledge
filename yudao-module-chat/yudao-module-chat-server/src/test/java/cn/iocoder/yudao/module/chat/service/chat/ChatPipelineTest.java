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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_CONTEXT_STALE;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_DOMAIN_UNAVAILABLE;
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
@MockitoSettings(strictness = Strictness.LENIENT)
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
        // RF-01: 每轮权限校验的默认通过态(当前用户可见 KB=6); 撤销/失败场景由特定测试覆盖
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(6L)));
        // RF2-01: 默认 KB=6 领域为 PATENT(与会话快照一致); 领域变化/失败场景由特定测试覆盖
        when(knowledgeApi.getKbDomainCodes(List.of(6L))).thenReturn(CommonResult.success(Map.of(6L, "PATENT")));
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
    void existingConversationRevalidatesKbVisibilityEachRound() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, 7L);

        verify(knowledgeApi).getVisibleKbIds(42L);
        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L)));
    }

    @Test
    void revokedKbCannotContinueConversation() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(1L)));

        assertThatThrownBy(() -> pipeline.send(100L, "问题", "web", null, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_BASE_NOT_EXISTS.getCode());

        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
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
    void legacyUnboundConversationCannotQueryAllKb() {
        AiConversationDO conversation = conversation(100L, null, 42L, "ACTIVE");
        conversation.setKbIds("0,5,7");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, null);

        assertThat(result.getConversationId()).isEqualTo(100L);
        assertThat(result.getAnswerable()).isFalse();
        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
        verify(knowledgeApi, never()).getVisibleKbIds(any());
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
    void exactMetadataRouteIsPreserved() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        response.setRoute(ChatRouteEnum.EXACT_METADATA);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "CN 122621758 A 一共有几项权利要求?", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.EXACT_METADATA);
    }

    @Test
    void exactClaimRouteIsPreserved() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        response.setRoute(ChatRouteEnum.EXACT_CLAIM);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "申请号 202311042981.1 权利要求1原文是什么?", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.EXACT_CLAIM);
    }

    @Test
    void generalEvidenceWithoutSectionTypeIsPreserved() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        // GENERAL 证据无 sectionType, 不应被丢弃
        response.setEvidence(List.of(evidenceItem(2091L, null)));
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "退换货政策是什么?", "web", null, 6L);

        assertThat(result.getEvidence()).hasSize(1);
        assertThat(result.getEvidence().get(0).getChunkId()).isEqualTo(2091L);
    }

    @Test
    void domainLookupFailureDoesNotCreateConversation() {
        when(knowledgeApi.getVisibleKbIds(42L)).thenReturn(CommonResult.success(Set.of(6L)));
        when(knowledgeApi.getKbDomainCodes(List.of(6L))).thenReturn(CommonResult.error(500, "domain rpc 失败"));

        assertThatThrownBy(() -> pipeline.send(null, "问题", "web", null, 6L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_DOMAIN_UNAVAILABLE.getCode());

        verify(conversationService, never()).createConversation(anyString(), anyString(), any(), anyString(), any());
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

    @Test
    void existingConversationWithMatchingDomainContinues() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(null);

        pipeline.send(100L, "问题", "web", null, 7L);

        verify(knowledgeApi).getKbDomainCodes(List.of(6L));
        verify(evidenceRpcAdapter).evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L)));
    }

    @Test
    void staleDomainRejectsWithContextStaleAndNeverCallsEvidence() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("GENERAL");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getKbDomainCodes(List.of(6L)))
                .thenReturn(CommonResult.success(Map.of(6L, "PATENT")));

        assertThatThrownBy(() -> pipeline.send(100L, "问题", "web", null, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_CONTEXT_STALE.getCode());

        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void existingConversationDomainLookupFailureIsKnowledgeDomainUnavailable() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        when(knowledgeApi.getKbDomainCodes(List.of(6L))).thenReturn(CommonResult.error(500, "domain rpc 失败"));

        assertThatThrownBy(() -> pipeline.send(100L, "问题", "web", null, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_DOMAIN_UNAVAILABLE.getCode());

        verify(evidenceRpcAdapter, never()).evaluate(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void ruleRouteIsPreserved() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("跨省寄送预计 3 天送达。");
        response.setRoute(ChatRouteEnum.RULE);
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "跨省寄送要多久?", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.RULE);
    }

    @Test
    void answerResultRouteIsNeverNullWhenEvaluationMissingRoute() {
        AiConversationDO conversation = conversation(100L, 6L, 42L, "ACTIVE");
        conversation.setDomainCode("PATENT");
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(conversation);
        EvidenceEvaluateRespDTO response = new EvidenceEvaluateRespDTO();
        response.setAnswerable(true);
        response.setAnswer("答案");
        response.setRoute(null); // 异常缺失时 Chat 兜底 ABSTAIN, 不允许 null(RF2-06)
        when(evidenceRpcAdapter.evaluate(any(), eq(7L), eq(42L), isNull(), anyList(), eq(List.of(6L))))
                .thenReturn(response);
        AiMessageDO userMessage = new AiMessageDO();
        userMessage.setId(3020L);
        AiMessageDO answerMessage = new AiMessageDO();
        answerMessage.setId(3021L);
        when(messageService.addMessage(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(userMessage, answerMessage);

        ChatSendResult result = pipeline.send(100L, "问题", "web", null, 6L);

        assertThat(result.getAnswerable()).isTrue();
        assertThat(result.getRoute()).isEqualTo(ChatRouteEnum.ABSTAIN);
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
