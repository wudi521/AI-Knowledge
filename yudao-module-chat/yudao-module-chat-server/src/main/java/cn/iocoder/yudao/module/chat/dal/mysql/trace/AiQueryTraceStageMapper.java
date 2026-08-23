package cn.iocoder.yudao.module.chat.dal.mysql.trace;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceStageDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 查询 Trace 阶段表 Mapper
 */
@Mapper
public interface AiQueryTraceStageMapper extends BaseMapperX<AiQueryTraceStageDO> {

    default List<AiQueryTraceStageDO> selectListByTraceId(String traceId) {
        return selectList(new LambdaQueryWrapper<AiQueryTraceStageDO>()
                .eq(AiQueryTraceStageDO::getTraceId, traceId)
                .orderByAsc(AiQueryTraceStageDO::getSeq));
    }

}
