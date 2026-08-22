package cn.iocoder.yudao.module.ingestion.api;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.store.EsChunkStore;
import cn.iocoder.yudao.module.ingestion.store.MilvusChunkStore;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 入库管线 对外 RPC 实现
 */
@Slf4j
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class IngestionApiImpl implements IngestionApi {

    @Resource
    private ChunkMapper chunkMapper;
    @Resource
    private EsChunkStore esChunkStore;
    @Resource
    private MilvusChunkStore milvusChunkStore;
    @Resource
    private KnowledgeApi knowledgeApi;

    @Override
    public Boolean triggerIngest(Long documentId) {
        return true;
    }

    @Override
    public CommonResult<Boolean> deleteDocumentData(Long documentId) {
        // 0. 解析文档全部版本 id(version_id 已是真实版本 id, 不能再按 documentId 查)
        List<Long> versionIds = knowledgeApi.getDocVersionIds(documentId).getCheckedData();
        // 1. 收集该文档全部版本下的 chunkId
        List<Long> chunkIds = new ArrayList<>();
        for (Long versionId : versionIds) {
            chunkIds.addAll(chunkMapper.selectListByVersionId(versionId).stream().map(ChunkDO::getId).toList());
        }
        // 2. MySQL 必删(本地事务, 幂等; 生产级语义: 删除文档必须成功)
        versionIds.forEach(chunkMapper::deleteByVersionId);
        // 3. ES/Milvus 尽力而为(P2-16): 失败仅告警, 向量残留由日志/后续清理兜底, 不阻断删除
        if (!chunkIds.isEmpty()) {
            try {
                esChunkStore.deleteChunks(chunkIds);
            } catch (Exception e) {
                log.warn("[deleteDocumentData][文档 {} ES 删除失败, 残留待清理: {}]", documentId, e.getMessage());
            }
            try {
                milvusChunkStore.deleteVectors(chunkIds);
            } catch (Exception e) {
                log.warn("[deleteDocumentData][文档 {} Milvus 删除失败, 残留待清理: {}]", documentId, e.getMessage());
            }
        }
        return success(true);
    }

    @Override
    public CommonResult<Boolean> deleteVersionIndex(Long versionId) {
        // P0-2: 版本过期/回滚时把该版本 chunk 从检索层移除——MySQL 置 DISABLED(保留审计, 检索只放行 PUBLISHED)
        // + ES/Milvus 删除, 保证旧版本内容不再参与检索(版本→chunk→索引 失效链闭环)
        List<Long> chunkIds = chunkMapper.selectListByVersionId(versionId).stream()
                .map(ChunkDO::getId).toList();
        chunkMapper.updateStatusByVersionId(versionId,
                cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum.DISABLED.getStatus());
        if (!chunkIds.isEmpty()) {
            try {
                esChunkStore.deleteChunks(chunkIds);
            } catch (Exception e) {
                log.warn("[deleteVersionIndex][版本 {} ES 删除失败, 残留待清理: {}]", versionId, e.getMessage());
            }
            try {
                milvusChunkStore.deleteVectors(chunkIds);
            } catch (Exception e) {
                log.warn("[deleteVersionIndex][版本 {} Milvus 删除失败, 残留待清理: {}]", versionId, e.getMessage());
            }
        }
        return success(true);
    }

    @Override
    public CommonResult<Boolean> indexVersion(Long versionId, Long kbId, Long tenantId, Long documentId) {
        // 幂等契约: 覆盖式重写 Milvus/ES; "置 chunk PUBLISHED"必须是最后一步
        List<ChunkDO> chunks = chunkMapper.selectListByVersionId(versionId);
        if (CollUtil.isEmpty(chunks)) {
            // 空版本无片段: 视为成功, 避免空向量写入 Milvus 报错
            return success(true);
        }
        List<Long> chunkIds = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        // ES 批量写入(P2-19: _bulk 一次网络往返, 替代逐条 PUT)
        List<Object[]> esItems = new ArrayList<>(chunks.size());
        for (ChunkDO chunk : chunks) {
            chunkIds.add(chunk.getId());
            vectors.add(parseEmbedding(chunk.getEmbedding()));
            esItems.add(new Object[]{chunk.getId(), tenantId, kbId, chunk.getContent(),
                    versionId, documentId, chunk.getChunkRole()});
        }
        esChunkStore.insertChunks(esItems);
        // Milvus 批量写
        milvusChunkStore.insertVectors(chunkIds, vectors, tenantId, kbId);
        // 最后: chunk 状态置 PUBLISHED
        chunkMapper.updateStatusByVersionId(versionId, cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum.PUBLISHED.getStatus());
        return success(true);
    }

    @Override
    public CommonResult<Boolean> hasUnpublishedChunks(Long versionId) {
        boolean exists = chunkMapper.existsUnpublishedByVersionId(versionId,
                cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum.PUBLISHED.getStatus());
        return success(exists);
    }

    private List<Float> parseEmbedding(String embeddingJson) {
        return cn.hutool.json.JSONUtil.toList(embeddingJson, Float.class);
    }

    @Override
    public CommonResult<List<ChunkRespDTO>> getChunksByVersion(Long versionId) {
        List<ChunkDO> chunks = chunkMapper.selectListByVersionId(versionId);
        List<ChunkRespDTO> result = chunks.stream().map(c -> {
            ChunkRespDTO dto = new ChunkRespDTO();
            dto.setId(c.getId());
            dto.setVersionId(c.getVersionId());
            dto.setContent(c.getContent());
            dto.setChunkType(c.getChunkType());
            return dto;
        }).toList();
        return success(result);
    }

    @Override
    public CommonResult<Map<Long, Boolean>> getChunkPublishMap(List<Long> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return success(Map.of());
        }
        List<ChunkDO> chunks = chunkMapper.selectBatchIds(chunkIds);
        Map<Long, Boolean> map = new HashMap<>();
        for (ChunkDO c : chunks) {
            map.put(c.getId(), "PUBLISHED".equals(c.getStatus()));
        }
        return success(map);
    }

    @Override
    public CommonResult<Map<Long, Long>> getChunkParents(List<Long> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return success(Map.of());
        }
        Map<Long, Long> map = new HashMap<>();
        for (ChunkDO c : chunkMapper.selectBatchIds(chunkIds)) {
            if (c.getParentId() != null) {
                map.put(c.getId(), c.getParentId());
            }
        }
        return success(map);
    }

    @Override
    public CommonResult<Map<Long, String>> getChunkContents(List<Long> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return success(Map.of());
        }
        List<ChunkDO> chunks = chunkMapper.selectBatchIds(chunkIds);
        Map<Long, String> map = new HashMap<>();
        for (ChunkDO c : chunks) {
            map.put(c.getId(), c.getContent());
        }
        return success(map);
    }

    @Override
    public CommonResult<Map<Long, ChunkDocInfoDTO>> getChunkDocInfo(List<Long> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return success(Map.of());
        }
        List<ChunkDO> chunks = chunkMapper.selectBatchIds(chunkIds);
        if (CollUtil.isEmpty(chunks)) {
            return success(Map.of());
        }
        // 1. chunkId -> versionId(片段表只落版本编号)
        List<Long> versionIds = chunks.stream().map(ChunkDO::getVersionId)
                .filter(Objects::nonNull).distinct().toList();
        // 2. versionId -> 版本信息(docId/versionNo)
        Map<Long, KnowledgeVersionRespDTO> versionMap = new HashMap<>();
        if (!versionIds.isEmpty()) {
            try {
                versionMap.putAll(knowledgeApi.getVersionMap(versionIds).getCheckedData());
            } catch (Exception e) {
                log.warn("[getChunkDocInfo][版本信息查询失败, 文档信息将缺失: {}]", e.getMessage());
            }
        }
        // 3. docId -> 文档信息(名称/产品, 批量查询避免逐条 Feign N+1)
        Map<Long, KnowledgeDocumentRespDTO> docInfoMap = new HashMap<>();
        List<Long> docIds = versionMap.values().stream().map(KnowledgeVersionRespDTO::getDocId)
                .filter(Objects::nonNull).distinct().toList();
        if (!docIds.isEmpty()) {
            try {
                docInfoMap.putAll(knowledgeApi.getDocumentMap(docIds).getCheckedData());
            } catch (Exception e) {
                log.warn("[getChunkDocInfo][文档信息批量查询失败: {}]", e.getMessage());
            }
        }
        // 4. 组装 chunkId -> 文档信息
        Map<Long, ChunkDocInfoDTO> map = new HashMap<>();
        for (ChunkDO chunk : chunks) {
            ChunkDocInfoDTO dto = new ChunkDocInfoDTO();
            dto.setChunkId(chunk.getId());
            KnowledgeVersionRespDTO version = versionMap.get(chunk.getVersionId());
            if (version != null) {
                dto.setDocumentId(version.getDocId());
                dto.setVersionNo(version.getVersionNo());
                KnowledgeDocumentRespDTO doc = docInfoMap.get(version.getDocId());
                if (doc != null) {
                    dto.setDocumentName(doc.getName());
                    dto.setProducts(doc.getProducts());
                }
            }
            map.put(chunk.getId(), dto);
        }
        return success(map);
    }

}
