package cn.iocoder.yudao.module.ingestion.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.store.EsChunkStore;
import cn.iocoder.yudao.module.ingestion.store.MilvusChunkStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 入库管线 对外 RPC 实现
 */
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class IngestionApiImpl implements IngestionApi {

    @Resource
    private ChunkMapper chunkMapper;
    @Resource
    private EsChunkStore esChunkStore;
    @Resource
    private MilvusChunkStore milvusChunkStore;

    @Override
    public Boolean triggerIngest(Long documentId) {
        return true;
    }

    @Override
    public CommonResult<Boolean> deleteDocumentData(Long documentId) {
        // 1. 查该文档所有 chunkId
        List<Long> chunkIds = chunkMapper.selectList(new LambdaQueryWrapper<ChunkDO>()
                        .eq(ChunkDO::getVersionId, documentId)
                        .select(ChunkDO::getId))
                .stream().map(ChunkDO::getId).toList();
        // 2. 删 ES
        esChunkStore.deleteChunks(chunkIds);
        // 3. 删 Milvus
        milvusChunkStore.deleteVectors(chunkIds);
        // 4. 删 MySQL(最后删, 失败可重试查)
        chunkMapper.deleteByVersionId(documentId);
        return success(true);
    }

    @Override
    public CommonResult<Boolean> indexVersion(Long versionId, Long kbId, Long tenantId) {
        // 幂等契约: 覆盖式重写 Milvus/ES; "置 chunk PUBLISHED"必须是最后一步
        List<ChunkDO> chunks = chunkMapper.selectListByVersionId(versionId);
        List<Long> chunkIds = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        for (ChunkDO chunk : chunks) {
            chunkIds.add(chunk.getId());
            vectors.add(parseEmbedding(chunk.getEmbedding()));
            // ES 写入
            esChunkStore.insertChunk(chunk.getId(), tenantId, kbId, chunk.getContent());
        }
        // Milvus 批量写
        milvusChunkStore.insertVectors(chunkIds, vectors, tenantId, kbId);
        // 最后: chunk 状态置 PUBLISHED
        chunkMapper.updateStatusByVersionId(versionId, "PUBLISHED");
        return success(true);
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

}
