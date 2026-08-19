package cn.iocoder.yudao.module.eval.dal.mysql.cases;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCasePageReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 评测用例 Mapper
 */
@Mapper
public interface EvalCaseMapper extends BaseMapperX<EvalCaseDO> {

    /**
     * 评测用例分页(租户由框架自动过滤; 最新在前)
     */
    default PageResult<EvalCaseDO> selectPage(EvalCasePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<EvalCaseDO>()
                .likeIfPresent(EvalCaseDO::getQuestion, reqVO.getQuestion())
                .eqIfPresent(EvalCaseDO::getKbId, reqVO.getKbId())
                .eqIfPresent(EvalCaseDO::getCategory, reqVO.getCategory())
                .orderByDesc(EvalCaseDO::getId));
    }

}
