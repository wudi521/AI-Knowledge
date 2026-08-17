package cn.iocoder.yudao.module.knowledge.dal.mysql.review;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
import cn.iocoder.yudao.module.knowledge.enums.review.ReviewItemStatusEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReviewItemMapper extends BaseMapperX<ReviewItemDO> {

    default List<ReviewItemDO> selectListByVersionId(Long versionId) {
        return selectList(new LambdaQueryWrapperX<ReviewItemDO>()
                .eq(ReviewItemDO::getVersionId, versionId)
                .orderByAsc(ReviewItemDO::getId));
    }

    default int deleteByVersionId(Long versionId) {
        return delete(new LambdaQueryWrapperX<ReviewItemDO>().eq(ReviewItemDO::getVersionId, versionId));
    }

    /** 是否存在未处理完(含驳回)的必审条目(发布门禁用) */
    default boolean existsUnfinishedRequired(Long versionId) {
        return selectCount(new LambdaQueryWrapperX<ReviewItemDO>()
                .eq(ReviewItemDO::getVersionId, versionId)
                .eq(ReviewItemDO::getMustReview, true)
                .in(ReviewItemDO::getStatus,
                        ReviewItemStatusEnum.PENDING.getStatus(), ReviewItemStatusEnum.REJECTED.getStatus())) > 0;
    }


}
