package cn.iocoder.yudao.module.knowledge.dal.mysql.acl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.acl.AiResourceAclDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 资源 ACL Mapper
 */
@Mapper
public interface AiResourceAclMapper extends BaseMapperX<AiResourceAclDO> {

    /** 查询资源上某动作的全部生效中 ACL(按主类型精确 + ALL 通配) */
    default List<AiResourceAclDO> selectByResource(String resourceType, Long resourceId, String action) {
        return selectList(new LambdaQueryWrapperX<AiResourceAclDO>()
                .eq(AiResourceAclDO::getResourceType, resourceType)
                .eq(AiResourceAclDO::getResourceId, resourceId)
                .eq(AiResourceAclDO::getAction, action));
    }

    /** 按资源批量查询(批量过滤用, 减少逐条查询) */
    default List<AiResourceAclDO> selectByResources(String resourceType, List<Long> resourceIds, String action) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<AiResourceAclDO>()
                .eq(AiResourceAclDO::getResourceType, resourceType)
                .in(AiResourceAclDO::getResourceId, resourceIds)
                .eq(AiResourceAclDO::getAction, action));
    }

}
