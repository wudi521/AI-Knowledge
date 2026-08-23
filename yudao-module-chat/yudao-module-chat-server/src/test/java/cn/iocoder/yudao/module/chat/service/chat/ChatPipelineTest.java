package cn.iocoder.yudao.module.chat.service.chat;

import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * ChatPipeline 单测: 新会话(conversationId=null)带 kbIds 时,
 * 必须先 createConversation 再 bindKbIds(新会话id), 禁止 bindKbIds(null, ...)。
 */
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

    private ChatPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new ChatPipeline();
        ReflectionTestUtils.setField(pipeline, "conversationService", conversationService);
        ReflectionTestUtils.setField(pipeline, "messageService", messageService);
        ReflectionTestUtils.setField(pipeline, "evidenceRpcAdapter", evidenceRpcAdapter);
        ReflectionTestUtils.setField(pipeline, "transferHandler", transferHandler);
        ReflectionTestUtils.setField(pipeline, "chatProperties", chatProperties);
        ReflectionTestUtils.setField(pipeline, "channelAdapters", List.<ChannelAdapter>of());
        when(chatProperties.getMaxContextMessages()).thenReturn(5);
        when(messageService.getRecentMessages(any(), anyInt())).thenReturn(List.of());
        when(transferHandler.detectTransferReason(anyString())).thenReturn(null);
    }

    @Test
    void newConversationCreatesBeforeBindingKb() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        when(conversationService.createConversation(anyString(), isNull())).thenReturn(conversation);
        // 评估返回 null → 走转人工兜底, 不进入生成路径
        when(evidenceRpcAdapter.evaluate(any(), any(), any(), any(), any(), anyList())).thenReturn(null);

        pipeline.send(null, "专利权利要求1是什么？", "web", null, List.of(6L));

        InOrder order = inOrder(conversationService);
        order.verify(conversationService).createConversation(eq("WEB"), isNull());
        order.verify(conversationService).bindKbIds(eq(100L), eq(List.of(6L)));
        // 禁止 bindKbIds(null, ...)
        verify(conversationService, never()).bindKbIds(isNull(), anyList());
    }
}
