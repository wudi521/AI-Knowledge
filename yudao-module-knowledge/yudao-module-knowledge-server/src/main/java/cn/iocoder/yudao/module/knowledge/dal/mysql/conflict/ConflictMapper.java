package cn.iocoder.yudao.module.knowledge.dal.mysql.conflict;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict.ConflictDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ConflictMapper extends BaseMapperX<ConflictDO> {

    default List<ConflictDO> selectListByVersionId(Long versionId) {
        return selectList(new LambdaQueryWrapperX<ConflictDO>()
                .eq(ConflictDO::getVersionId, versionId));
    }

    default boolean existsPendingByVersionId(Long versionId) {
        return selectCount(new LambdaQueryWrapperX<ConflictDO>()
                .eq(ConflictDO::getVersionId, versionId)
                .eq(ConflictDO::getStatus, "PENDING")) > 0;
    }

    default List<ConflictDO> selectListByDocIdAndStatus(Long docId, String status) {
        return selectList(new LambdaQueryWrapperX<ConflictDO>()
                .eq(ConflictDO::getDocId, docId)
                .eqIfPresent(ConflictDO::getStatus, status)
                .orderByDesc(ConflictDO::getId));
    }
}
