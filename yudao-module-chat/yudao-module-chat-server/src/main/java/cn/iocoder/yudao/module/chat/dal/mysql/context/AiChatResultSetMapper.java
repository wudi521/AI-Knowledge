package cn.iocoder.yudao.module.chat.dal.mysql.context;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 多轮查询结果集快照 Mapper(ai_chat_result_set)
 */
@Mapper
public interface AiChatResultSetMapper extends BaseMapperX<AiChatResultSetDO> {

    default AiChatResultSetDO selectByResultSetId(String resultSetId) {
        return selectOne(new LambdaQueryWrapper<AiChatResultSetDO>()
                .eq(AiChatResultSetDO::getResultSetId, resultSetId));
    }

    /** CQ-02/47 幂等: 该 queryId 是否已有结果集快照(SSE 重试/重复提交去重) */
    default boolean existsByQueryId(String queryId) {
        return selectCount(new LambdaQueryWrapper<AiChatResultSetDO>()
                .eq(AiChatResultSetDO::getQueryId, queryId)) > 0;
    }

    default List<AiChatResultSetDO> selectByConversationId(Long conversationId) {
        return selectList(new LambdaQueryWrapper<AiChatResultSetDO>()
                .eq(AiChatResultSetDO::getConversationId, conversationId)
                .orderByDesc(AiChatResultSetDO::getId));
    }

}
