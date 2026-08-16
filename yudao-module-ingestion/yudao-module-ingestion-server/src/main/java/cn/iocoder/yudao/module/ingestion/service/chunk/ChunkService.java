package cn.iocoder.yudao.module.ingestion.service.chunk;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;

import java.util.List;

/**
 * AI 知识片段 Service 接口
 */
public interface ChunkService {

    /**
     * 获得 AI 知识片段分页
     *
     * @param pageReqVO 分页查询条件(其中 documentId 映射 ai_chunk.version_id)
     * @return 分页结果
     */
    PageResult<ChunkDO> getChunkPage(ChunkPageReqVO pageReqVO);

    /**
     * 编辑 AI 知识片段内容(仅更新 content)
     *
     * @param id      片段编号
     * @param content 片段内容
     */
    void updateChunk(Long id, String content);

    /**
     * 启用/禁用 AI 知识片段(仅更新 status, PUBLISHED=启用 / DISABLED=禁用)
     *
     * @param id     片段编号
     * @param status 状态值
     */
    void updateChunkStatus(Long id, String status);

    /**
     * 删除片段(MySQL + ES + Milvus 三处联动)
     *
     * @param id 片段编号
     */
    void deleteChunk(Long id);

    /**
     * 批量删除片段(MySQL + ES + Milvus 三处联动)
     *
     * @param ids 片段编号列表
     */
    void deleteChunks(List<Long> ids);

}
