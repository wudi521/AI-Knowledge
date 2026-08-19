package cn.iocoder.yudao.module.chat.dal.mysql.feedback;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.chat.dal.dataobject.feedback.AiFeedbackDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 反馈 Mapper
 */
@Mapper
public interface AiFeedbackMapper extends BaseMapperX<AiFeedbackDO> {

}
