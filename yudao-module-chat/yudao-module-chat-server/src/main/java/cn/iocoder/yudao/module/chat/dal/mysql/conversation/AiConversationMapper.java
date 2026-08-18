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

}
