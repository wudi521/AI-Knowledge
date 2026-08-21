package cn.iocoder.yudao.module.rule.dal.mysql.rule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRulePageReqVO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 硬规则 Mapper(租户过滤由 TenantBaseDO 自动生效)
 */
@Mapper
public interface AiRuleMapper extends BaseMapperX<AiRuleDO> {

    default List<AiRuleDO> selectByKeyAndStatusIn(String key, List<Integer> statuses) {
        return selectList(new LambdaQueryWrapperX<AiRuleDO>()
                .eq(AiRuleDO::getRuleKey, key)
                .in(AiRuleDO::getStatus, statuses)
                .orderByDesc(AiRuleDO::getVersion));
    }

    default List<AiRuleDO> selectByKeyOrdered(String key) {
        return selectList(new LambdaQueryWrapperX<AiRuleDO>()
                .eq(AiRuleDO::getRuleKey, key)
                .orderByDesc(AiRuleDO::getVersion));
    }

    default PageResult<AiRuleDO> selectPage(AiRulePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiRuleDO>()
                .eqIfPresent(AiRuleDO::getRuleKey, reqVO.getRuleKey())
                .eqIfPresent(AiRuleDO::getStatus, reqVO.getStatus())
                .orderByDesc(AiRuleDO::getId));
    }

}
