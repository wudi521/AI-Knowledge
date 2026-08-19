package cn.iocoder.yudao.module.eval.dal.mysql.task;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskPageReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 评测任务 Mapper
 */
@Mapper
public interface EvalTaskMapper extends BaseMapperX<EvalTaskDO> {

    /**
     * 评测任务分页(租户由框架自动过滤; 最新在前)
     */
    default PageResult<EvalTaskDO> selectPage(EvalTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<EvalTaskDO>()
                .eqIfPresent(EvalTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(EvalTaskDO::getKbId, reqVO.getKbId())
                .orderByDesc(EvalTaskDO::getId));
    }

}
