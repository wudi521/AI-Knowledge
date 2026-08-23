package cn.iocoder.yudao.module.chat.dal.mysql.message;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * AI 会话消息 - 证据快照 Mapper
 */
@Mapper
public interface AiMessageEvidenceMapper extends BaseMapperX<AiMessageEvidenceDO> {

    /**
     * 查询多条消息的证据快照(按消息编号 + 证据序号升序, 保证 [Cn] 映射顺序稳定)
     */
    default List<AiMessageEvidenceDO> selectListByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<AiMessageEvidenceDO>()
                .in(AiMessageEvidenceDO::getMessageId, messageIds)
                .orderByAsc(AiMessageEvidenceDO::getMessageId)
                .orderByAsc(AiMessageEvidenceDO::getEvidenceIndex));
    }

}
