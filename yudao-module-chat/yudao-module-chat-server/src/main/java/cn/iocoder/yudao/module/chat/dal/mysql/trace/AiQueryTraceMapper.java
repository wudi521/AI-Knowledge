package cn.iocoder.yudao.module.chat.dal.mysql.trace;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 查询 Trace 主表 Mapper
 */
@Mapper
public interface AiQueryTraceMapper extends BaseMapperX<AiQueryTraceDO> {

    default AiQueryTraceDO selectByTraceId(String traceId) {
        return selectOne(new LambdaQueryWrapper<AiQueryTraceDO>()
                .eq(AiQueryTraceDO::getTraceId, traceId)
                .last("LIMIT 1"));
    }

}
