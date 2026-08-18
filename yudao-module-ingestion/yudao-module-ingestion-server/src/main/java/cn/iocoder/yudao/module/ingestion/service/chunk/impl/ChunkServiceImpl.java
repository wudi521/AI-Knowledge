package cn.iocoder.yudao.module.ingestion.service.chunk.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum;
import cn.iocoder.yudao.module.ingestion.service.chunk.ChunkService;
import cn.iocoder.yudao.module.ingestion.store.EsChunkStore;
import cn.iocoder.yudao.module.ingestion.store.MilvusChunkStore;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_NOT_EXISTS;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_STATUS_ERROR;

/**
 * AI 知识片段 Service 实现
 */
@Service
@Validated
public class ChunkServiceImpl implements ChunkService {

    @Resource
    private ChunkMapper chunkMapper;

    @Resource
    private EsChunkStore esChunkStore;

    @Resource
    private MilvusChunkStore milvusChunkStore;

    @Resource
    private KnowledgeApi knowledgeApi;

    @Override
    public PageResult<ChunkDO> getChunkPage(ChunkPageReqVO pageReqVO) {
        // documentId 过滤: 先解析文档的全部版本 id, 再按 version_id IN 过滤(version_id 已是真实版本 id)
        if (pageReqVO.getDocumentId() != null) {
            List<Long> versionIds = knowledgeApi.getDocVersionIds(pageReqVO.getDocumentId()).getCheckedData();
            return chunkMapper.selectPageByVersionIds(pageReqVO, versionIds);
        }
        return chunkMapper.selectPage(pageReqVO);
    }

    @Override
    public ChunkDO getChunk(Long id) {
        return chunkMapper.selectById(id);
    }

    @Override
    public void updateChunk(Long id, String content) {
        // 校验片段存在
        validateChunkExists(id);
        // 仅更新内容
        ChunkDO update = new ChunkDO();
        update.setId(id);
        update.setContent(content);
        chunkMapper.updateById(update);
    }

    @Override
    public void updateChunkStatus(Long id, String status) {
        // 校验状态值合法(PUBLISHED=启用 / DISABLED=禁用)
        validateChunkStatus(status);
        // 校验片段存在
        validateChunkExists(id);
        // 仅更新状态
        ChunkDO update = new ChunkDO();
        update.setId(id);
        update.setStatus(status);
        chunkMapper.updateById(update);
    }

    @Override
    public void deleteChunk(Long id) {
        // 校验片段存在(单条删除保持原校验行为)
        validateChunkExists(id);
        // 委托批量删除, 三处联动行为不变
        deleteChunks(List.of(id));
    }

    @Override
    public void deleteChunks(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 批量场景不逐个校验: ids 不存在时 ES/Milvus 删除为幂等 no-op, MySQL deleteBatchIds 亦幂等
        // 三处联动: ES 批量 → Milvus 批量 → MySQL 批量(最后删, 失败可重试)
        esChunkStore.deleteChunks(ids);
        milvusChunkStore.deleteVectors(ids);
        chunkMapper.deleteBatchIds(ids);
    }

    private void validateChunkExists(Long id) {
        if (chunkMapper.selectById(id) == null) {
            throw exception(CHUNK_NOT_EXISTS);
        }
    }

    private void validateChunkStatus(String status) {
        if (ChunkStatusEnum.fromStatus(status) == null) {
            throw exception(CHUNK_STATUS_ERROR);
        }
    }

}
