package cn.iocoder.yudao.module.model.dal.mysql.prompt;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptPageReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.prompt.AiPromptDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI Prompt Mapper
 */
@Mapper
public interface AiPromptMapper extends BaseMapperX<AiPromptDO> {

    default List<AiPromptDO> selectByKeyAndStatusIn(String key, List<Integer> statuses) {
        return selectList(new LambdaQueryWrapperX<AiPromptDO>()
                .eq(AiPromptDO::getPromptKey, key)
                .in(AiPromptDO::getStatus, statuses)
                .orderByDesc(AiPromptDO::getVersion));
    }

    default List<AiPromptDO> selectByKeyOrdered(String key) {
        return selectList(new LambdaQueryWrapperX<AiPromptDO>()
                .eq(AiPromptDO::getPromptKey, key)
                .orderByDesc(AiPromptDO::getVersion));
    }

    default PageResult<AiPromptDO> selectPage(AiPromptPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiPromptDO>()
                .eqIfPresent(AiPromptDO::getPromptKey, reqVO.getPromptKey())
                .eqIfPresent(AiPromptDO::getStatus, reqVO.getStatus())
                .orderByDesc(AiPromptDO::getId));
    }

}
