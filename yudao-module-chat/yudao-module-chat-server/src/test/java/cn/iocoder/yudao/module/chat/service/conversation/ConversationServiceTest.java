package cn.iocoder.yudao.module.chat.service.conversation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.mysql.conversation.AiConversationMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_CONTEXT_CONFLICT;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private AiConversationMapper mapper;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService();
        ReflectionTestUtils.setField(service, "aiConversationMapper", mapper);
    }

    @Test
    void createKnowledgeConversationPersistsBindingAndOwner() {
        AiConversationDO created = service.createConversation("WEB", null, 6L, "PATENT", 42L);

        ArgumentCaptor<AiConversationDO> captor = ArgumentCaptor.forClass(AiConversationDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getKbId()).isEqualTo(6L);
        assertThat(captor.getValue().getDomainCode()).isEqualTo("PATENT");
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
        assertThat(created).isSameAs(captor.getValue());
    }

    @Test
    void rebindKnowledgeConversationRejectsDifferentKb() {
        AiConversationDO conversation = conversation(9L, 6L, 42L);
        when(mapper.selectById(9L)).thenReturn(conversation);

        assertThatThrownBy(() -> service.ensureBoundKb(9L, 7L, 42L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_CONTEXT_CONFLICT.getCode());
        verify(mapper, never()).updateById(any(AiConversationDO.class));
        verify(mapper, never()).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
    }

    @Test
    void ensureBoundKbWithSameKbDoesNotUpdate() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, 6L, 42L));

        service.ensureBoundKb(9L, 6L, 42L);

        verify(mapper, never()).updateById(any(AiConversationDO.class));
        verify(mapper, never()).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
    }

    @Test
    void ensureBoundKbWithNullKbDoesNotUpdateAfterOwnerCheck() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, 6L, 42L));

        service.ensureBoundKb(9L, null, 42L);

        verify(mapper, never()).updateById(any(AiConversationDO.class));
        verify(mapper, never()).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
    }

    @Test
    void ensureBoundKbRejectsWrongUserWithoutUpdating() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, null, 42L));

        assertThatThrownBy(() -> service.ensureBoundKb(9L, 6L, 43L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_NOT_EXISTS.getCode());
        verify(mapper, never()).updateById(any(AiConversationDO.class));
        verify(mapper, never()).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
    }

    @Test
    void ensureBoundKbUsesAtomicNullBindingUpdate() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, null, 42L));
        when(mapper.update(isNull(AiConversationDO.class), any(UpdateWrapper.class))).thenReturn(1);

        service.ensureBoundKb(9L, 7L, 42L);

        ArgumentCaptor<UpdateWrapper<AiConversationDO>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(AiConversationDO.class), captor.capture());
        UpdateWrapper<AiConversationDO> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("id =");
        assertThat(wrapper.getSqlSegment()).contains("kb_id IS NULL");
        assertThat(wrapper.getSqlSegment()).contains("user_id =");
        assertThat(wrapper.getSqlSet()).contains("kb_id=");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(7L);
        assertThat(wrapper.getParamNameValuePairs()).containsValue(9L);
        assertThat(wrapper.getParamNameValuePairs()).containsValue(42L);
        verify(mapper, never()).updateById(any(AiConversationDO.class));
    }

    @Test
    void ensureBoundKbReReadsAfterAtomicUpdateLosesRace() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, null, 42L), conversation(9L, 7L, 42L));
        when(mapper.update(isNull(AiConversationDO.class), any(UpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.ensureBoundKb(9L, 6L, 42L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_CONTEXT_CONFLICT.getCode());
        verify(mapper).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
        verify(mapper, atLeast(2)).selectById(9L);
    }

    @Test
    void ensureBoundKbReturnsWhenCompetitorAlreadyBoundRequestedKb() {
        when(mapper.selectById(9L)).thenReturn(conversation(9L, null, 42L), conversation(9L, 7L, 42L));
        when(mapper.update(isNull(AiConversationDO.class), any(UpdateWrapper.class))).thenReturn(0);

        service.ensureBoundKb(9L, 7L, 42L);

        verify(mapper).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
        verify(mapper, atLeast(2)).selectById(9L);
    }

    @Test
    void ensureBoundKbUsesLegacyCreatorOwnerCondition() {
        AiConversationDO conversation = conversation(9L, null, null);
        conversation.setCreator("42");
        when(mapper.selectById(9L)).thenReturn(conversation);
        when(mapper.update(isNull(AiConversationDO.class), any(UpdateWrapper.class))).thenReturn(1);

        service.ensureBoundKb(9L, 7L, 42L);

        ArgumentCaptor<UpdateWrapper<AiConversationDO>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(AiConversationDO.class), captor.capture());
        UpdateWrapper<AiConversationDO> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("id =");
        assertThat(wrapper.getSqlSegment()).contains("kb_id IS NULL");
        assertThat(wrapper.getSqlSegment()).contains("user_id IS NULL");
        assertThat(wrapper.getSqlSegment()).contains("creator =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(9L);
        assertThat(wrapper.getParamNameValuePairs()).containsValue(7L);
        assertThat(wrapper.getParamNameValuePairs()).containsValue("42");
    }

    @Test
    void getConversationForUserReturnsOnlyMatchingOwner() {
        AiConversationDO conversation = conversation(9L, 6L, 42L);
        when(mapper.selectById(9L)).thenReturn(conversation);

        assertThat(service.getConversationForUser(9L, 42L)).isSameAs(conversation);
        assertThat(service.getConversationForUser(9L, 43L)).isNull();
    }

    @Test
    void getConversationForUserUsesNumericCreatorForLegacyRows() {
        AiConversationDO conversation = conversation(9L, 6L, null);
        conversation.setCreator("42");
        when(mapper.selectById(9L)).thenReturn(conversation);

        assertThat(service.getConversationForUser(9L, 42L)).isSameAs(conversation);
    }

    @Test
    void getConversationForUserDoesNotTreatNonNumericCreatorAsOwner() {
        AiConversationDO conversation = conversation(9L, 6L, null);
        conversation.setCreator("legacy-user");
        when(mapper.selectById(9L)).thenReturn(conversation);

        assertThat(service.getConversationForUser(9L, 42L)).isNull();
    }

    @Test
    void legacyNumericCreatorCannotBindForWrongUser() {
        AiConversationDO conversation = conversation(9L, null, null);
        conversation.setCreator("42");
        when(mapper.selectById(9L)).thenReturn(conversation);

        assertThatThrownBy(() -> service.ensureBoundKb(9L, 6L, 43L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(CONVERSATION_NOT_EXISTS.getCode());
        verify(mapper, never()).update(isNull(AiConversationDO.class), any(UpdateWrapper.class));
    }

    private AiConversationDO conversation(Long id, Long kbId, Long userId) {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(id);
        conversation.setKbId(kbId);
        conversation.setUserId(userId);
        return conversation;
    }

}
