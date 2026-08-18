package cn.iocoder.yudao.module.knowledge.dal.mysql.intent;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 意图 Mapper
 */
@Mapper
public interface AiIntentMapper extends BaseMapperX<AiIntentDO> {

    /**
     * 查询知识库下全部意图(含停用, 按 id 升序)
     */
    default List<AiIntentDO> selectListByKbId(Long kbId) {
        return selectList(new LambdaQueryWrapperX<AiIntentDO>()
                .eq(AiIntentDO::getKbId, kbId)
                .orderByAsc(AiIntentDO::getId));
    }

    /**
     * 查询知识库下启用中的意图(status=0, 检索分类用)
     */
    default List<AiIntentDO> selectEnabledByKbId(Long kbId) {
        return selectList(new LambdaQueryWrapperX<AiIntentDO>()
                .eq(AiIntentDO::getKbId, kbId)
                .eq(AiIntentDO::getStatus, 0)
                .orderByAsc(AiIntentDO::getId));
    }

}
