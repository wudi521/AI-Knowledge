package cn.iocoder.yudao.module.ingestion.service.chunk.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum;
import cn.iocoder.yudao.module.ingestion.service.chunk.ChunkService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

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

    @Override
    public PageResult<ChunkDO> getChunkPage(ChunkPageReqVO pageReqVO) {
        // 查询条件中的 documentId 由 ChunkMapper 映射到 ai_chunk.version_id
        return chunkMapper.selectPage(pageReqVO);
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
