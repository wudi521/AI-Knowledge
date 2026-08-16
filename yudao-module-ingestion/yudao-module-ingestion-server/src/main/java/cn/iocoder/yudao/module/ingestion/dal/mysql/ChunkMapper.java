package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ChunkMapper extends BaseMapperX<ChunkDO> {

    /**
     * 分页查询片段(ChunkDO.versionId 即版本编号, 与文档 id 非一一对应)
     *
     * @param reqVO 分页查询条件(未按文档过滤时 documentId 为 null)
     * @return 分页结果
     */
    default PageResult<ChunkDO> selectPage(ChunkPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ChunkDO>()
                .eqIfPresent(ChunkDO::getVersionId, reqVO.getDocumentId())
                .eqIfPresent(ChunkDO::getChunkType, reqVO.getChunkType())
                .eqIfPresent(ChunkDO::getStatus, reqVO.getStatus())
                .orderByDesc(ChunkDO::getId));
    }

    /**
     * 分页查询片段(按版本编号集合过滤, 文档过滤时使用: documentId -> 版本 ids)
     *
     * @param reqVO 分页查询条件
     * @param versionIds 版本编号集合
     * @return 分页结果
     */
    default PageResult<ChunkDO> selectPageByVersionIds(ChunkPageReqVO reqVO, List<Long> versionIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ChunkDO>()
                .inIfPresent(ChunkDO::getVersionId, versionIds)
                .eqIfPresent(ChunkDO::getChunkType, reqVO.getChunkType())
                .eqIfPresent(ChunkDO::getStatus, reqVO.getStatus())
                .orderByDesc(ChunkDO::getId));
    }

    /**
     * 按版本删除旧片段
     * 重试/重发前清理残留数据, 保证入库幂等(ai_chunk 无唯一约束)
     *
     * @param versionId 版本编号
     * @return 删除行数
     */
    default int deleteByVersionId(Long versionId) {
        return delete(new LambdaQueryWrapper<ChunkDO>().eq(ChunkDO::getVersionId, versionId));
    }

    /** 按版本查询片段 */
    default List<ChunkDO> selectListByVersionId(Long versionId) {
        return selectList(new LambdaQueryWrapper<ChunkDO>().eq(ChunkDO::getVersionId, versionId));
    }

    /** 按版本更新片段状态 */
    default int updateStatusByVersionId(Long versionId, String status) {
        return update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChunkDO>()
                .eq(ChunkDO::getVersionId, versionId)
                .set(ChunkDO::getStatus, status));
    }

}
