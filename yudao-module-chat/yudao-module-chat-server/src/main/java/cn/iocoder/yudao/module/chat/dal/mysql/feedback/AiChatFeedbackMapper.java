package cn.iocoder.yudao.module.chat.dal.mysql.feedback;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.feedback.AiChatFeedbackDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 回答反馈 Mapper(ai_chat_feedback)
 */
@Mapper
public interface AiChatFeedbackMapper extends BaseMapperX<AiChatFeedbackDO> {

    default AiChatFeedbackDO selectByMessageId(Long messageId) {
        return selectOne(new LambdaQueryWrapperX<AiChatFeedbackDO>()
                .eq(AiChatFeedbackDO::getMessageId, messageId));
    }

    /** 当前租户下反馈总数(基础统计, P0) */
    default Long selectCountAll() {
        return selectCount();
    }

}
