package cn.iocoder.yudao.module.knowledge.dal.mysql.entity;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiRelationDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

/**
 * 关系 Mapper
 */
@Mapper
public interface AiRelationMapper extends BaseMapperX<AiRelationDO> {

    /** 主体向外关系(active + 有效期内; 图遍历用) */
    default List<AiRelationDO> selectOutgoing(Long subjectEntityId, String predicate) {
        return selectList(new LambdaQueryWrapperX<AiRelationDO>()
                .eq(AiRelationDO::getSubjectEntityId, subjectEntityId)
                .eq(AiRelationDO::getStatus, "ACTIVE")
                .eqIfPresent(AiRelationDO::getPredicate, predicate)
                .and(w -> w.isNull(AiRelationDO::getValidTo).or().ge(AiRelationDO::getValidTo, LocalDate.now())));
    }

    /** 主体+谓词+客体/值 幂等查询(SPO 冲突比较与去重) */
    default List<AiRelationDO> selectBySpo(Long subjectEntityId, String predicate, Long objectEntityId, String objectValue) {
        return selectList(new LambdaQueryWrapperX<AiRelationDO>()
                .eq(AiRelationDO::getSubjectEntityId, subjectEntityId)
                .eq(AiRelationDO::getPredicate, predicate)
                .eq(AiRelationDO::getObjectEntityId, objectEntityId)
                .eq(AiRelationDO::getObjectValue, objectValue)
                .eq(AiRelationDO::getStatus, "ACTIVE"));
    }

}
