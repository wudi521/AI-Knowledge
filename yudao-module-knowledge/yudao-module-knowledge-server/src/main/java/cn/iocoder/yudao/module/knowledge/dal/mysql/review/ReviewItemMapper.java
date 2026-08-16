package cn.iocoder.yudao.module.knowledge.dal.mysql.review;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
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

    /** 是否存在未处理完(含驳回)的必审条目 */
    default boolean existsUnfinishedRequired(Long versionId) {
        return selectCount(new LambdaQueryWrapperX<ReviewItemDO>()
                .eq(ReviewItemDO::getVersionId, versionId)
                .eq(ReviewItemDO::getMustReview, true)
                .in(ReviewItemDO::getStatus, "PENDING", "REJECTED")) > 0;
    }

    /** 价格类双人复核是否全部完成(PRICE 条目 reviewer2 非空且与 reviewer 不同) */
    default boolean existsPriceWithoutDoubleReview(Long versionId) {
        return selectCount(new LambdaQueryWrapperX<ReviewItemDO>()
                .eq(ReviewItemDO::getVersionId, versionId)
                .eq(ReviewItemDO::getItemType, "PRICE")
                .eq(ReviewItemDO::getStatus, "APPROVED")
                .and(w -> w.isNull(ReviewItemDO::getReviewer2)
                        .or().apply("reviewer = reviewer2"))) > 0;
    }

}
