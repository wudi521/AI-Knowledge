package cn.iocoder.yudao.module.model.dal.mysql.calllog;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.model.dal.dataobject.calllog.AiModelCallLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型调用计量 Mapper
 */
@Mapper
public interface AiModelCallLogMapper extends BaseMapperX<AiModelCallLogDO> {
    // 计量表仅追加查询; 预留按租户/类型聚合(成本管理 M8 消费)
}
