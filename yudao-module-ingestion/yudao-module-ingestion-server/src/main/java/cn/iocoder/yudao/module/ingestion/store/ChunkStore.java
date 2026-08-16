package cn.iocoder.yudao.module.ingestion.store;

import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;

import java.util.List;

/**
 * 知识片段三写存储(MySQL + Milvus + ES)
 */
public interface ChunkStore {

    /**
     * 批量写入
     *
     * @param chunks 已含 vector 的片段
     * @param tenantId 租户编号
     * @param kbId 知识库编号
     */
    void saveChunks(List<ChunkDO> chunks, Long tenantId, Long kbId);

}
