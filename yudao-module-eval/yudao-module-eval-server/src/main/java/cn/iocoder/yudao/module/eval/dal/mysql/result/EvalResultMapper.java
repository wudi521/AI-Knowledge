package cn.iocoder.yudao.module.eval.dal.mysql.result;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.eval.dal.dataobject.result.EvalResultDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 评测逐题结果 Mapper
 */
@Mapper
public interface EvalResultMapper extends BaseMapperX<EvalResultDO> {

    /**
     * 按任务查询逐题结果(按编号升序, 与执行顺序一致)
     */
    default List<EvalResultDO> selectListByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<EvalResultDO>()
                .eq(EvalResultDO::getTaskId, taskId)
                .orderByAsc(EvalResultDO::getId));
    }

}
