package cn.iocoder.yudao.module.ingestion.dal.mysql;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     * 注意: versionIds 为空时直接返回空页(不能退化为无过滤查全表)
     *
     * @param reqVO 分页查询条件
     * @param versionIds 版本编号集合
     * @return 分页结果
     */
    default PageResult<ChunkDO> selectPageByVersionIds(ChunkPageReqVO reqVO, List<Long> versionIds) {
        if (CollUtil.isEmpty(versionIds)) {
            return PageResult.empty();
        }
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

    /**
     * 按版本物理删除旧片段(重试入库用)
     * <p>
     * 不能用 MyBatis-Plus 逻辑删除: deleted=1 的旧行仍占用 uk_tenant_version_key(tenant_id,
     * version_id, chunk_key), 而 chunkKey 每次重排都从 c000000 开始, 必然唯一键冲突。
     */
    @InterceptorIgnore(tenantLine = "true") // version_id 全局唯一, 无需租户条件
    @Delete("DELETE FROM ai_chunk WHERE version_id = #{versionId}")
    int deleteByVersionIdPhysical(@Param("versionId") Long versionId);

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

    /** 版本下是否存在未发布(非 PUBLISHED)片段 */
    default boolean existsUnpublishedByVersionId(Long versionId, String publishedStatus) {
        return selectCount(new LambdaQueryWrapper<ChunkDO>()
                .eq(ChunkDO::getVersionId, versionId)
                .ne(ChunkDO::getStatus, publishedStatus)) > 0;
    }

}
