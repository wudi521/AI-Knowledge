package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChunkMapper extends BaseMapperX<ChunkDO> {

    /**
     * 按版本(暂为文档 id)删除旧片段
     * 重试/重发前清理残留数据, 保证入库幂等(ai_chunk 无唯一约束)
     *
     * @param versionId 版本编号
     * @return 删除行数
     */
    default int deleteByVersionId(Long versionId) {
        return delete(new LambdaQueryWrapper<ChunkDO>().eq(ChunkDO::getVersionId, versionId));
    }

}
