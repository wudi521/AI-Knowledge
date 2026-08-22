package cn.iocoder.yudao.module.knowledge.dal.mysql.outbox;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.outbox.AiOutboxEventDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Outbox 事件 Mapper
 */
@Mapper
public interface AiOutboxEventMapper extends BaseMapperX<AiOutboxEventDO> {

    default AiOutboxEventDO selectByAggregate(String aggregateType, Long aggregateId, String eventType) {
        return selectOne(new LambdaQueryWrapperX<AiOutboxEventDO>()
                .eq(AiOutboxEventDO::getAggregateType, aggregateType)
                .eq(AiOutboxEventDO::getAggregateId, aggregateId)
                .eq(AiOutboxEventDO::getEventType, eventType)
                .last("LIMIT 1"));
    }

    default List<AiOutboxEventDO> selectPending(int limit) {
        return selectList(new LambdaQueryWrapperX<AiOutboxEventDO>()
                .eq(AiOutboxEventDO::getStatus, "PENDING")
                .orderByAsc(AiOutboxEventDO::getId)
                .last("LIMIT " + limit));
    }

}
