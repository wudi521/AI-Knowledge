package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库槽位定义 Mapper
 */
@Mapper
public interface AiKnowledgeBaseSlotMapper extends BaseMapperX<AiKnowledgeBaseSlotDO> {

    default PageResult<AiKnowledgeBaseSlotDO> selectPage(AiKnowledgeBaseSlotPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                .eqIfPresent(AiKnowledgeBaseSlotDO::getKbId, reqVO.getKbId())
                .eqIfPresent(AiKnowledgeBaseSlotDO::getSlotCode, reqVO.getSlotCode())
                .eqIfPresent(AiKnowledgeBaseSlotDO::getStatus, reqVO.getStatus())
                .orderByAsc(AiKnowledgeBaseSlotDO::getKbId)
                .orderByAsc(AiKnowledgeBaseSlotDO::getSort));
    }

}
