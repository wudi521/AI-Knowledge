package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionTaskDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 入库阶段 Trace Mapper
 */
@Mapper
public interface AiIngestionTaskMapper extends BaseMapperX<AiIngestionTaskDO> {

    default List<AiIngestionTaskDO> selectByJobId(Long jobId) {
        return selectList(new LambdaQueryWrapperX<AiIngestionTaskDO>()
                .eq(AiIngestionTaskDO::getJobId, jobId)
                .orderByAsc(AiIngestionTaskDO::getStageOrder));
    }
}
