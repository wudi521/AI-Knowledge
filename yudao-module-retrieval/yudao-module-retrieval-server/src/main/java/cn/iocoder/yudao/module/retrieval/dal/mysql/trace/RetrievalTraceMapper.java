package cn.iocoder.yudao.module.retrieval.dal.mysql.trace;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.RetrievalTraceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索追踪 Mapper(F5)
 */
@Mapper
public interface RetrievalTraceMapper extends BaseMapperX<RetrievalTraceDO> {
}
