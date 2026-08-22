package cn.iocoder.yudao.module.retrieval.dal.mysql.trace;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.QueryStageDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 查询阶段 Trace Mapper
 */
@Mapper
public interface QueryStageMapper extends BaseMapperX<QueryStageDO> {

    default List<QueryStageDO> selectByTraceId(String traceId) {
        return selectList(new LambdaQueryWrapperX<QueryStageDO>()
                .eq(QueryStageDO::getTraceId, traceId)
                .orderByAsc(QueryStageDO::getStageOrder));
    }
}
