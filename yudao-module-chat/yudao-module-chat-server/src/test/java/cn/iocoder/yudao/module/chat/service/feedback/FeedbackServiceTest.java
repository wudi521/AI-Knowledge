package cn.iocoder.yudao.module.chat.service.feedback;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackStatsRespVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.feedback.AiChatFeedbackDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceDO;
import cn.iocoder.yudao.module.chat.dal.mysql.feedback.AiChatFeedbackMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.message.AiMessageMapper;
import cn.iocoder.yudao.module.chat.enums.feedback.FeedbackRatingEnum;
import cn.iocoder.yudao.module.chat.enums.feedback.FeedbackReasonEnum;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.trace.QueryTraceService;
import cn.iocoder.yudao.module.eval.api.EvalApi;
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

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_NOT_ALLOWED;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_RATING_INVALID;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_REASON_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    @Mock
    private AiChatFeedbackMapper aiChatFeedbackMapper;
    @Mock
    private AiMessageMapper aiMessageMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private QueryTraceService queryTraceService;
    @Mock
    private EvalApi evalApi;

    private FeedbackService service;
    private MockedStatic<SecurityFrameworkUtils> securityFrameworkUtils;

    @BeforeEach
    void setUp() {
        service = new FeedbackService();
        ReflectionTestUtils.setField(service, "aiChatFeedbackMapper", aiChatFeedbackMapper);
        ReflectionTestUtils.setField(service, "aiMessageMapper", aiMessageMapper);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "queryTraceService", queryTraceService);
        ReflectionTestUtils.setField(service, "evalApi", evalApi);
        securityFrameworkUtils = org.mockito.Mockito.mockStatic(SecurityFrameworkUtils.class);
        securityFrameworkUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
    }

    @AfterEach
    void tearDown() {
        securityFrameworkUtils.close();
    }

    @Test
    void upsertHelpfulPersistsWithAutoContext() {
        AiMessageDO message = aiMessage(3021L, 100L, "q-abc123", "SCOPED_RAG");
        when(aiMessageMapper.selectById(3021L)).thenReturn(message);
        // owner 校验通过
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(
                new cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO());
        AiQueryTraceDO trace = new AiQueryTraceDO();
        trace.setKbId(6L);
        trace.setDomainCode("PATENT");
        trace.setTotalMs(8120L);
        trace.setRoute("SCOPED_RAG");
        when(queryTraceService.getTrace("q-abc123")).thenReturn(trace);
        AiMessageEvidenceDO evidence = evidenceRow(2091L, 6L, "PATENT");
        when(messageService.getEvidenceByMessageId(3021L)).thenReturn(List.of(evidence));

        service.upsert(3021L, FeedbackRatingEnum.HELPFUL.getRating(), null, null);

        ArgumentCaptor<AiChatFeedbackDO> captor = ArgumentCaptor.forClass(AiChatFeedbackDO.class);
        verify(aiChatFeedbackMapper).insert(captor.capture());
        AiChatFeedbackDO saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo(3021L);
        assertThat(saved.getQueryTraceId()).isEqualTo("q-abc123");
        assertThat(saved.getRoute()).isEqualTo("SCOPED_RAG");
        assertThat(saved.getKbId()).isEqualTo(6L);
        assertThat(saved.getDomainCode()).isEqualTo("PATENT");
        assertThat(saved.getLatencyMs()).isEqualTo(8120L);
        assertThat(saved.getPrimaryDocumentId()).isEqualTo(66L);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getEvidenceSnapshot()).contains("2091");
    }

    @Test
    void upsertNotHelpfulRequiresReason() {
        AiMessageDO message = aiMessage(3021L, 100L, null, null);
        when(aiMessageMapper.selectById(3021L)).thenReturn(message);

        assertThatThrownBy(() -> service.upsert(3021L, FeedbackRatingEnum.NOT_HELPFUL.getRating(), null, null))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(FEEDBACK_REASON_INVALID.getCode());
    }

    @Test
    void upsertInvalidRatingThrows() {
        assertThatThrownBy(() -> service.upsert(3021L, "INVALID", null, null))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(FEEDBACK_RATING_INVALID.getCode());
    }

    @Test
    void upsertNotOwnerRejected() {
        AiMessageDO message = aiMessage(3021L, 100L, null, null);
        when(aiMessageMapper.selectById(3021L)).thenReturn(message);
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> service.upsert(3021L, FeedbackRatingEnum.HELPFUL.getRating(), null, null))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(FEEDBACK_NOT_ALLOWED.getCode());
    }

    @Test
    void upsertUpdatesExistingFeedbackByMessageId() {
        AiMessageDO message = aiMessage(3021L, 100L, null, null);
        when(aiMessageMapper.selectById(3021L)).thenReturn(message);
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(
                new cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO());
        AiChatFeedbackDO existing = new AiChatFeedbackDO();
        existing.setId(999L);
        existing.setMessageId(3021L);
        existing.setRating(FeedbackRatingEnum.HELPFUL.getRating());
        when(aiChatFeedbackMapper.selectByMessageId(3021L)).thenReturn(existing);

        Long id = service.upsert(3021L, FeedbackRatingEnum.NOT_HELPFUL.getRating(),
                FeedbackReasonEnum.WRONG_EVIDENCE.getCode(), "引用不对");

        assertThat(id).isEqualTo(999L);
        assertThat(existing.getRating()).isEqualTo(FeedbackRatingEnum.NOT_HELPFUL.getRating());
        assertThat(existing.getReasonCode()).isEqualTo(FeedbackReasonEnum.WRONG_EVIDENCE.getCode());
        assertThat(existing.getComment()).isEqualTo("引用不对");
        verify(aiChatFeedbackMapper).updateById(existing);
    }

    @Test
    void upsertNotHelpfulTriggersEvalCaseAsync() {
        AiMessageDO message = aiMessage(3021L, 100L, null, null);
        message.setContent("被反馈的问题内容");
        when(aiMessageMapper.selectById(3021L)).thenReturn(message);
        when(conversationService.getConversationForUser(100L, 42L)).thenReturn(
                new cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO());
        when(evalApi.createCaseFromFeedback(isNull(), eq("被反馈的问题内容"), isNull()))
                .thenReturn(CommonResult.success(5001L));

        service.upsert(3021L, FeedbackRatingEnum.NOT_HELPFUL.getRating(),
                FeedbackReasonEnum.WRONG_ANSWER.getCode(), null);

        verify(evalApi, timeout(2000)).createCaseFromFeedback(isNull(), eq("被反馈的问题内容"), isNull());
    }

    @Test
    void getByMessageIdReturnsNullWhenAbsent() {
        assertThat(service.getByMessageId(3021L)).isNull();
    }

    @Test
    void statsComputesRates() {
        when(aiChatFeedbackMapper.selectCountAll()).thenReturn(10L);
        when(aiChatFeedbackMapper.selectCount(any())).thenReturn(7L, 3L);

        FeedbackStatsRespVO stats = service.stats();

        assertThat(stats.getTotalCount()).isEqualTo(10L);
        assertThat(stats.getHelpfulCount()).isEqualTo(7L);
        assertThat(stats.getNotHelpfulCount()).isEqualTo(3L);
        assertThat(stats.getHelpfulRate()).isEqualTo(0.7d);
        assertThat(stats.getNotHelpfulRate()).isEqualTo(0.3d);
    }

    private AiMessageDO aiMessage(Long id, Long conversationId, String queryTraceId, String route) {
        AiMessageDO message = new AiMessageDO();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole("AI");
        message.setContent("回答内容");
        message.setQueryTraceId(queryTraceId);
        message.setRoute(route);
        message.setConfidence(BigDecimal.valueOf(0.93));
        return message;
    }

    private AiMessageEvidenceDO evidenceRow(Long chunkId, Long kbId, String domainCode) {
        AiMessageEvidenceDO row = new AiMessageEvidenceDO();
        row.setChunkId(chunkId);
        row.setDocumentId(66L);
        row.setKbId(kbId);
        row.setDomainCode(domainCode);
        row.setDocumentName("CN 122604134 A");
        row.setContentSnapshot("引用原文快照");
        return row;
    }

}
