package cn.iocoder.yudao.module.knowledge.dal.mysql.entity;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiEntityDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 实体 Mapper
 */
@Mapper
public interface AiEntityMapper extends BaseMapperX<AiEntityDO> {

    default AiEntityDO selectByCanonicalName(String canonicalName) {
        return selectOne(new LambdaQueryWrapperX<AiEntityDO>()
                .eq(AiEntityDO::getCanonicalName, canonicalName)
                .last("LIMIT 1"));
    }

    default AiEntityDO selectByNormalizedName(String normalizedName) {
        return selectOne(new LambdaQueryWrapperX<AiEntityDO>()
                .eq(AiEntityDO::getNormalizedName, normalizedName)
                .last("LIMIT 1"));
    }

}
