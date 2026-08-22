package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 入库任务 Mapper
 */
@Mapper
public interface AiIngestionJobMapper extends BaseMapperX<AiIngestionJobDO> {

    default AiIngestionJobDO selectByDocument(Long documentId) {
        return selectOne(new LambdaQueryWrapperX<AiIngestionJobDO>()
                .eq(AiIngestionJobDO::getDocumentId, documentId)
                .eq(AiIngestionJobDO::getJobType, "INGEST")
                .last("LIMIT 1"));
    }

}
