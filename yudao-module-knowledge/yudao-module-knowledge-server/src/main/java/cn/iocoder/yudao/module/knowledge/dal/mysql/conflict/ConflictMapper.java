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

    /** 删除版本下的全部冲突记录(文档级联删除用) */
    default int deleteByVersionId(Long versionId) {
        return delete(new LambdaQueryWrapperX<ConflictDO>().eq(ConflictDO::getVersionId, versionId));
    }

    /** 只清待裁决记录(保留已裁决审计历史, 防止"裁决以新版为准"后被重建的死循环) */
    default int deletePendingByVersionId(Long versionId) {
        return delete(new LambdaQueryWrapperX<ConflictDO>()
                .eq(ConflictDO::getVersionId, versionId)
                .eq(ConflictDO::getStatus, "PENDING"));
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
