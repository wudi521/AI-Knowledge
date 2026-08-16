package cn.iocoder.yudao.module.knowledge.dal.mysql.version;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AiDocVersionMapper extends BaseMapperX<AiDocVersionDO> {

    default AiDocVersionDO selectLatestByDocId(Long docId) {
        return selectOne(new LambdaQueryWrapperX<AiDocVersionDO>()
                .eq(AiDocVersionDO::getDocId, docId)
                .orderByDesc(AiDocVersionDO::getId)
                .last("LIMIT 1"));
    }

    default AiDocVersionDO selectPublishedByDocId(Long docId) {
        return selectOne(new LambdaQueryWrapperX<AiDocVersionDO>()
                .eq(AiDocVersionDO::getDocId, docId)
                .eq(AiDocVersionDO::getStatus, "PUBLISHED")
                .orderByDesc(AiDocVersionDO::getId)
                .last("LIMIT 1"));
    }

    default List<AiDocVersionDO> selectListByDocId(Long docId) {
        return selectList(new LambdaQueryWrapperX<AiDocVersionDO>()
                .eq(AiDocVersionDO::getDocId, docId)
                .orderByDesc(AiDocVersionDO::getId));
    }
}
