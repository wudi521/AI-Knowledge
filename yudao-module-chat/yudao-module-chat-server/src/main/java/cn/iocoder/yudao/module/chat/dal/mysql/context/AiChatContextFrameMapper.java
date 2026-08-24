package cn.iocoder.yudao.module.chat.dal.mysql.context;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 多轮查询上下文帧 Mapper(ai_chat_context_frame)
 */
@Mapper
public interface AiChatContextFrameMapper extends BaseMapperX<AiChatContextFrameDO> {

    /** 查询会话最近 N 帧(倒序, 最近在前) */
    default List<AiChatContextFrameDO> selectRecentByConversationId(Long conversationId, int limit) {
        return selectList(new LambdaQueryWrapper<AiChatContextFrameDO>()
                .eq(AiChatContextFrameDO::getConversationId, conversationId)
                .orderByDesc(AiChatContextFrameDO::getSeq)
                .last("LIMIT " + limit));
    }

    /** 删除会话中 seq 小于 minSeq 的旧帧(帧栈保留最近 N) */
    default void deleteOlderThan(Long conversationId, int minSeq) {
        delete(new LambdaQueryWrapper<AiChatContextFrameDO>()
                .eq(AiChatContextFrameDO::getConversationId, conversationId)
                .lt(AiChatContextFrameDO::getSeq, minSeq));
    }

}
