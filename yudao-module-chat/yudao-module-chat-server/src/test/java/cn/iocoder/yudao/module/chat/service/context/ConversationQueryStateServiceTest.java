package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.mysql.conversation.AiConversationMapper;
import cn.iocoder.yudao.module.chat.service.context.model.ConversationQueryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationQueryStateServiceTest {

    @Mock
    private AiConversationMapper aiConversationMapper;

    private ConversationQueryStateService service;

    @BeforeEach
    void setUp() {
        service = new ConversationQueryStateService();
        ReflectionTestUtils.setField(service, "aiConversationMapper", aiConversationMapper);
    }

    @Test
    void getQueryStateParsesJson() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        conversation.setQueryState("{\"lastResultSetId\":\"rs-1\",\"entityType\":\"PATENT_DOCUMENT\",\"entityCount\":4}");
        when(aiConversationMapper.selectById(100L)).thenReturn(conversation);

        ConversationQueryState state = service.getQueryState(100L);

        assertThat(state).isNotNull();
        assertThat(state.getLastResultSetId()).isEqualTo("rs-1");
        assertThat(state.getEntityType()).isEqualTo("PATENT_DOCUMENT");
        assertThat(state.getEntityCount()).isEqualTo(4);
    }

    @Test
    void getQueryStateReturnsNullWhenAbsent() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        when(aiConversationMapper.selectById(100L)).thenReturn(conversation);

        assertThat(service.getQueryState(100L)).isNull();
    }

    @Test
    void updateQueryStateWritesJson() {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(100L);
        when(aiConversationMapper.selectById(100L)).thenReturn(conversation);

        service.updateQueryState(100L, ConversationQueryState.builder()
                .lastResultSetId("rs-9").entityType("PATENT_DOCUMENT").entityCount(3)
                .lastMetric("CLAIM_COUNT").build());

        verify(aiConversationMapper).updateById(conversation);
        assertThat(conversation.getQueryState()).contains("\"rs-9\"");
        assertThat(conversation.getQueryState()).contains("CLAIM_COUNT");
    }

}
