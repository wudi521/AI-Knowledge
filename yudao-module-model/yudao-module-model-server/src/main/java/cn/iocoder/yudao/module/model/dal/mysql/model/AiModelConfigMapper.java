package cn.iocoder.yudao.module.model.dal.mysql.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigPageReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置 Mapper
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapperX<AiModelConfigDO> {

    default PageResult<AiModelConfigDO> selectPage(AiModelConfigPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiModelConfigDO>()
                .likeIfPresent(AiModelConfigDO::getName, reqVO.getName())
                .eqIfPresent(AiModelConfigDO::getType, reqVO.getType())
                .eqIfPresent(AiModelConfigDO::getScenario, reqVO.getScenario())
                .eqIfPresent(AiModelConfigDO::getStatus, reqVO.getStatus())
                .orderByDesc(AiModelConfigDO::getId));
    }

}
