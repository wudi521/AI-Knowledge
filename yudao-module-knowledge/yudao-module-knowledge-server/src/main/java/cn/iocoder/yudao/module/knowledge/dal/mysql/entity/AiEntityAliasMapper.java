package cn.iocoder.yudao.module.knowledge.dal.mysql.entity;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiEntityAliasDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 实体别名 Mapper
 */
@Mapper
public interface AiEntityAliasMapper extends BaseMapperX<AiEntityAliasDO> {

    default AiEntityAliasDO selectByAlias(String alias) {
        return selectOne(new LambdaQueryWrapperX<AiEntityAliasDO>()
                .eq(AiEntityAliasDO::getAlias, alias)
                .last("LIMIT 1"));
    }

    default List<AiEntityAliasDO> selectByEntityId(Long entityId) {
        return selectList(new LambdaQueryWrapperX<AiEntityAliasDO>()
                .eq(AiEntityAliasDO::getEntityId, entityId));
    }

}
