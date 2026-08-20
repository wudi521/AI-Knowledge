package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 知识库槽位定义 Mapper
 */
@Mapper
public interface AiKnowledgeBaseSlotMapper extends BaseMapperX<AiKnowledgeBaseSlotDO> {

    default PageResult<AiKnowledgeBaseSlotDO> selectPage(AiKnowledgeBaseSlotPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                .eqIfPresent(AiKnowledgeBaseSlotDO::getKbId, reqVO.getKbId())
                .eqIfPresent(AiKnowledgeBaseSlotDO::getSlotCode, reqVO.getSlotCode())
                .eqIfPresent(AiKnowledgeBaseSlotDO::getStatus, reqVO.getStatus())
                .orderByAsc(AiKnowledgeBaseSlotDO::getKbId)
                .orderByAsc(AiKnowledgeBaseSlotDO::getSort));
    }

    /**
     * 物理删除该知识库全部 LLM_AUTO 槽位(自动生成覆盖用; 带租户过滤防越权)。
     * 注意: 不用逻辑删除——uk(kb_id,slot_code,deleted) 组合下逻辑删除+重插会在第二次替换时唯一键冲突;
     * LLM_AUTO 行是机器生成、可再生, 物理删除不损失审计价值; MANUAL 行不受影响。
     */
    @Delete("DELETE FROM ai_knowledge_base_slot WHERE kb_id = #{kbId} AND tenant_id = #{tenantId} AND source = 'LLM_AUTO' AND deleted = 0")
    int deleteAutoByKbId(@Param("kbId") Long kbId, @Param("tenantId") Long tenantId);

}
