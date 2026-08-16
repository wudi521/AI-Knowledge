package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 文档 Mapper
 */
@Mapper
public interface AiDocumentMapper extends BaseMapperX<AiDocumentDO> {

    default PageResult<AiDocumentDO> selectPage(AiDocumentPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiDocumentDO>()
                .eqIfPresent(AiDocumentDO::getKbId, reqVO.getKbId())
                .likeIfPresent(AiDocumentDO::getName, reqVO.getName())
                .eqIfPresent(AiDocumentDO::getParseStatus, reqVO.getParseStatus())
                .orderByDesc(AiDocumentDO::getId));
    }

    default int updateParseStatus(Long id, String parseStatus, Integer chunkCount, String errorMsg) {
        return update(null, new LambdaUpdateWrapper<AiDocumentDO>()
                .eq(AiDocumentDO::getId, id)
                .set(AiDocumentDO::getParseStatus, parseStatus)
                .set(chunkCount != null, AiDocumentDO::getChunkCount, chunkCount)
                .set(errorMsg != null, AiDocumentDO::getErrorMsg, errorMsg));
    }

}
