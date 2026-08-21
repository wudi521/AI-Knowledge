package cn.iocoder.yudao.module.rule.dal.mysql.rule;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleHitDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 规则命中留痕 Mapper(租户过滤由 TenantBaseDO 自动生效)
 */
@Mapper
public interface AiRuleHitMapper extends BaseMapperX<AiRuleHitDO> {
}
