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

    /**
     * 查询会话最近 limit 条 USER/AI 消息(排除 SYSTEM 交接消息), 按创建时间降序(最新在前)
     * <p>
     * 供历史上下文读取: 调用方 {@code Collections.reverse} 后即为聊天顺序(升序)。
     * create_time 为秒级精度, 同秒并列消息以 id(自增, 严格递增) 作为次级排序键保证确定性;
     * limit 为配置值(整数), 拼接安全; 为 0 时返回空集, 不报错。
     */
    default List<AiMessageDO> selectRecentByConversationId(Long conversationId, int limit) {
        return selectList(new LambdaQueryWrapper<AiMessageDO>()
                .eq(AiMessageDO::getConversationId, conversationId)
                .in(AiMessageDO::getRole, "USER", "AI")
                .orderByDesc(AiMessageDO::getCreateTime)
                .orderByDesc(AiMessageDO::getId)
                .last("LIMIT " + Math.max(limit, 0)));
    }

}
