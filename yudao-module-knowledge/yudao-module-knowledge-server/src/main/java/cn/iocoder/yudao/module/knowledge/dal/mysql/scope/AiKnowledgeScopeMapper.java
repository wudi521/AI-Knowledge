package cn.iocoder.yudao.module.knowledge.dal.mysql.scope;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.scope.AiKnowledgeScopeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识业务范围 Mapper
 */
@Mapper
public interface AiKnowledgeScopeMapper extends BaseMapperX<AiKnowledgeScopeDO> {

    /** 按知识库批量查询生效中的范围(检索硬过滤用) */
    default List<AiKnowledgeScopeDO> selectByKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<AiKnowledgeScopeDO>()
                .in(AiKnowledgeScopeDO::getKbId, kbIds)
                .orderByAsc(AiKnowledgeScopeDO::getScopePriority));
    }

    /** 按类型+编码查询生效中的知识库范围(检索命中 slot 时过滤) */
    default List<AiKnowledgeScopeDO> selectByScope(String scopeType, String scopeCode) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeScopeDO>()
                .eq(AiKnowledgeScopeDO::getScopeType, scopeType)
                .eq(AiKnowledgeScopeDO::getScopeCode, scopeCode));
    }

}
