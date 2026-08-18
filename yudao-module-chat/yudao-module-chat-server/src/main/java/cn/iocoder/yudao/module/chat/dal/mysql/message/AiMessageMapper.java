package cn.iocoder.yudao.module.chat.dal.mysql.message;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 会话消息 Mapper
 */
@Mapper
public interface AiMessageMapper extends BaseMapperX<AiMessageDO> {

    /**
     * 查询会话的全部消息, 按创建时间升序(聊天顺序)
     */
    default List<AiMessageDO> selectListByConversationId(Long conversationId) {
        return selectList(new LambdaQueryWrapper<AiMessageDO>()
                .eq(AiMessageDO::getConversationId, conversationId)
                .orderByAsc(AiMessageDO::getCreateTime));
    }

}
