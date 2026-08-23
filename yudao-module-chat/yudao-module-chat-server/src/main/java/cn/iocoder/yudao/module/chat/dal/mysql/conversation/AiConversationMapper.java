package cn.iocoder.yudao.module.chat.dal.mysql.conversation;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationPageReqVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话 Mapper
 */
@Mapper
public interface AiConversationMapper extends BaseMapperX<AiConversationDO> {

    /**
     * 会话分页(租户由框架自动过滤)
     * <p>
     * 排序: create_time 倒序(最新会话在前)。"待人工接单(TRANSFERRED)优先"由前端按状态徽标分组展示,
     * 后端保持单一片区排序, 避免 FIELD 自定义排序带来的深分页/索引失效问题。
     */
    default PageResult<AiConversationDO> selectPage(ConversationPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiConversationDO>()
                .eqIfPresent(AiConversationDO::getStatus, reqVO.getStatus())
                .orderByDesc(AiConversationDO::getCreateTime));
    }

    /**
     * 当前用户会话分页(租户由框架自动过滤)
     * <p>
     * 用户范围: user_id = 当前用户 或(user_id IS NULL 且 creator = 当前用户, 迁移期旧记录)。
     * 仅用于会话列表归属隔离, 不进行全租户扫描。
     */
    default PageResult<AiConversationDO> selectMyPage(ConversationPageReqVO reqVO, Long userId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiConversationDO>()
                .eqIfPresent(AiConversationDO::getStatus, reqVO.getStatus())
                .and(w -> w.eq(AiConversationDO::getUserId, userId)
                        .or(q -> q.isNull(AiConversationDO::getUserId)
                                .eq(AiConversationDO::getCreator, String.valueOf(userId))))
                .orderByDesc(AiConversationDO::getCreateTime));
    }

}
