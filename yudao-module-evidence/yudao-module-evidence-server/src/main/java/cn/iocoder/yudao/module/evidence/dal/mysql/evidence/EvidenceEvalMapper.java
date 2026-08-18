package cn.iocoder.yudao.module.evidence.dal.mysql.evidence;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.evidence.dal.dataobject.evidence.EvidenceEvalDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 证据评估会话 Mapper
 */
@Mapper
public interface EvidenceEvalMapper extends BaseMapperX<EvidenceEvalDO> {
}
