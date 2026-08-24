package cn.iocoder.yudao.module.chat.service.context;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.mysql.conversation.AiConversationMapper;
import cn.iocoder.yudao.module.chat.service.context.model.ConversationQueryState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 会话轻量查询状态(ConversationQueryState, CQ-01/35)
 * <p>
 * 服务端 Source of Truth, 持久化在 ai_conversation.query_state(JSON)。刷新浏览器/重开会话可恢复。
 * 只存引用与计数, 不存大 ID 集; 大结果集在 ai_chat_result_set, 通过 resultSetId 引用。
 */
@Slf4j
@Service
public class ConversationQueryStateService {

    @Resource
    private AiConversationMapper aiConversationMapper;

    /** 读取会话的查询状态; 无则返回 null(不生成空状态) */
    public ConversationQueryState getQueryState(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        AiConversationDO conversation = aiConversationMapper.selectById(conversationId);
        if (conversation == null || StrUtil.isBlank(conversation.getQueryState())) {
            return null;
        }
        try {
            return JSONUtil.toBean(conversation.getQueryState(), ConversationQueryState.class);
        } catch (Exception e) {
            log.warn("[getQueryState][会话({}) query_state 解析失败, 忽略: {}]", conversationId, e.getMessage());
            return null;
        }
    }

    /** 写入会话的查询状态(尽力而为: 失败仅告警不阻断主流程) */
    public void updateQueryState(Long conversationId, ConversationQueryState state) {
        if (conversationId == null || state == null) {
            return;
        }
        try {
            AiConversationDO conversation = aiConversationMapper.selectById(conversationId);
            if (conversation == null) {
                return;
            }
            state.setLastUpdatedAt(LocalDateTime.now());
            conversation.setQueryState(JSONUtil.toJsonStr(state));
            aiConversationMapper.updateById(conversation);
        } catch (Exception e) {
            log.warn("[updateQueryState][会话({}) 状态落库失败, 忽略: {}]", conversationId, e.getMessage());
        }
    }

}
