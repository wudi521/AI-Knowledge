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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_NOT_EXISTS;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_STATUS_ERROR;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_KB_NOT_VISIBLE;

/**
 * AI 知识片段 Service 实现
 */
@Slf4j
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
        // 越权防线: 片段所属知识库不可见时禁止编辑
        validateChunkKbVisible(chunkMapper.selectById(id));
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
        // 越权防线: 片段所属知识库不可见时禁止启停
        validateChunkKbVisible(chunkMapper.selectById(id));
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
        // 越权防线: 片段所属知识库不可见时禁止删除
        validateChunkKbVisible(chunkMapper.selectById(id));
        // 委托批量删除, 三处联动行为不变
        deleteChunks(List.of(id));
    }

    @Override
    public void deleteChunks(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 生产级删除语义(P2-16): MySQL 必删(本地事务), ES/Milvus 尽力而为——
        // 向量/倒排残留由日志记录待清理, 绝不因外部存储故障阻断文档删除
        // 1. MySQL 先删(本地事务, 幂等)
        chunkMapper.deleteBatchIds(ids);
        // 2. ES/Milvus 尽力而为: 失败仅告警(残留由后续清理任务兜底)
        try {
            esChunkStore.deleteChunks(ids);
        } catch (Exception e) {
            log.warn("[deleteChunks][ES 删除失败, 向量残留待清理: chunkIds={} 原因: {}]", ids, e.getMessage());
        }
        try {
            milvusChunkStore.deleteVectors(ids);
        } catch (Exception e) {
            log.warn("[deleteChunks][Milvus 删除失败, 向量残留待清理: chunkIds={} 原因: {}]", ids, e.getMessage());
        }
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

    /**
     * 越权防线: 片段所属文档的知识库对当前用户不可见时禁止写操作。
     * 链路: chunk.versionId → 版本.docId → 文档.kbId → 用户可见 KB 集合对比。
     * 无登录态(内部调用/RPC)直通; 超管由 getVisibleKbIds 内部放行(返回全部); 任一跳缺失保守拒绝。
     */
    private void validateChunkKbVisible(ChunkDO chunk) {
        Long userId = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return; // 无登录态(内部调用/RPC)直通
        }
        try {
            // 1. 版本 → 文档 id
            Map<Long, cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO> vMap =
                    knowledgeApi.getVersionMap(List.of(chunk.getVersionId())).getCheckedData();
            cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO version =
                    vMap == null ? null : vMap.get(chunk.getVersionId());
            Long docId = version == null ? null : version.getDocId();
            if (docId == null) {
                throw exception(CHUNK_KB_NOT_VISIBLE);
            }
            // 2. 文档 → 知识库 id
            Map<Long, cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO> dMap =
                    knowledgeApi.getDocumentMap(List.of(docId)).getCheckedData();
            cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO doc =
                    dMap == null ? null : dMap.get(docId);
            Long kbId = doc == null ? null : doc.getKbId();
            if (kbId == null) {
                throw exception(CHUNK_KB_NOT_VISIBLE);
            }
            // 3. 知识库 ∈ 用户可见集合
            Set<Long> visible = knowledgeApi.getVisibleKbIds(userId).getCheckedData();
            if (visible == null || !visible.contains(kbId)) {
                throw exception(CHUNK_KB_NOT_VISIBLE);
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[validateChunkKbVisible][可见性校验 RPC 异常, 保守拒绝: {}]", e.getMessage());
            throw exception(CHUNK_KB_NOT_VISIBLE);
        }
    }

}
