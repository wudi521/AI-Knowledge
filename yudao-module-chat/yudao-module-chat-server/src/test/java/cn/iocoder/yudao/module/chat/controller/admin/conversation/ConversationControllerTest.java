package cn.iocoder.yudao.module.chat.controller.admin.conversation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationHistoryRespVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageService messageService;
    @Mock
    private TransferHandler transferHandler;

    private ConversationController controller;
    private MockedStatic<SecurityFrameworkUtils> securityFrameworkUtils;

    @BeforeEach
    void setUp() {
        controller = new ConversationController();
        ReflectionTestUtils.setField(controller, "conversationService", conversationService);
        ReflectionTestUtils.setField(controller, "messageService", messageService);
        ReflectionTestUtils.setField(controller, "transferHandler", transferHandler);
        securityFrameworkUtils = org.mockito.Mockito.mockStatic(SecurityFrameworkUtils.class);
        securityFrameworkUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
    }

    @AfterEach
    void tearDown() {
        securityFrameworkUtils.close();
    }

    @Test
    void historyRejectsMissingOrUnauthorizedConversationBeforeReadingMessages() {
        when(conversationService.getConversationForUser(9L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> controller.history(9L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_NOT_EXISTS.getCode());

        verify(messageService, never()).getMessages(any());
    }

    @Test
    void historyMapsKnowledgeContextAndOwnerFields() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(9L);
        conversation.setKbId(6L);
        conversation.setDomainCode("PATENT");
        conversation.setUserId(42L);
        when(conversationService.getConversationForUser(9L, 42L)).thenReturn(conversation);
        when(messageService.getMessages(9L)).thenReturn(List.of());

        CommonResult<ConversationHistoryRespVO> result = controller.history(9L);

        assertThat(result.getData().getConversation().getKbId()).isEqualTo(6L);
        assertThat(result.getData().getConversation().getDomainCode()).isEqualTo("PATENT");
        assertThat(result.getData().getConversation().getUserId()).isEqualTo(42L);
        verify(conversationService).getConversationForUser(9L, 42L);
        verify(messageService).getMessages(9L);
    }

}
