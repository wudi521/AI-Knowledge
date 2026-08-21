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

    /**
     * 查询指定知识库最新一条 DONE 任务(闸门检查用; 租户由框架自动过滤)
     * <p>
     * 状态常量见 {@link cn.iocoder.yudao.module.eval.service.runner.EvalRunner} 的 STATUS_DONE,
     * 此处不依赖 service 包, 直接使用字面量避免包层级反向引用。
     */
    default EvalTaskDO selectLatestDoneByKbId(Long kbId) {
        return selectOne(new LambdaQueryWrapperX<EvalTaskDO>()
                .eq(EvalTaskDO::getKbId, kbId)
                .eq(EvalTaskDO::getStatus, "DONE")
                .orderByDesc(EvalTaskDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 是否存在运行中的任务(可重入防护: 同知识库已有 RUNNING 任务时拒绝新任务)
     * <p>
     * kbId 为空(全部用例任务)时按"任意 RUNNING 任务"判断, 保证全局同一时刻仅一个评测在执行
     */
    default boolean existsRunning(Long kbId) {
        return selectCount(new LambdaQueryWrapperX<EvalTaskDO>()
                .eq(EvalTaskDO::getStatus, "RUNNING")
                .eqIfPresent(EvalTaskDO::getKbId, kbId)) > 0;
    }

}
