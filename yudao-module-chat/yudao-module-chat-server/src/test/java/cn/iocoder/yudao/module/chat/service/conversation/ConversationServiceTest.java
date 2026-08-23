package cn.iocoder.yudao.module.chat.service.conversation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationPageReqVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.mysql.conversation.AiConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_DOMAIN_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void getMyConversationPageForwardsUserScope() {
        ConversationPageReqVO reqVO = new ConversationPageReqVO();
        when(mapper.selectMyPage(reqVO, 42L)).thenReturn(new PageResult<>());

        PageResult<AiConversationDO> result = service.getMyConversationPage(reqVO, 42L);

        assertThat(result).isNotNull();
        verify(mapper).selectMyPage(reqVO, 42L);
    }

    @Test
    void createBoundConversationRejectsBlankDomain() {
        assertThatThrownBy(() -> service.createConversation("WEB", null, 6L, "  ", 42L))
                .isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(KNOWLEDGE_DOMAIN_UNAVAILABLE.getCode());
        verify(mapper, never()).insert(any(AiConversationDO.class));
    }

    private AiConversationDO conversation(Long id, Long kbId, Long userId) {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setId(id);
        conversation.setKbId(kbId);
        conversation.setUserId(userId);
        return conversation;
    }

}
