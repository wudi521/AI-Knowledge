package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库 Mapper
 */
@Mapper
public interface AiKnowledgeBaseMapper extends BaseMapperX<AiKnowledgeBaseDO> {

    default PageResult<AiKnowledgeBaseDO> selectPage(AiKnowledgeBasePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiKnowledgeBaseDO>()
                .likeIfPresent(AiKnowledgeBaseDO::getName, reqVO.getName())
                .eqIfPresent(AiKnowledgeBaseDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(AiKnowledgeBaseDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(AiKnowledgeBaseDO::getId));
    }

}
