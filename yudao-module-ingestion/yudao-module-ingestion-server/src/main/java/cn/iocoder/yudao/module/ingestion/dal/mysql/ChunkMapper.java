package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChunkMapper extends BaseMapperX<ChunkDO> {
}
