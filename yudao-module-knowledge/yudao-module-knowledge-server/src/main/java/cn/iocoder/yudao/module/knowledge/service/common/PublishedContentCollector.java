package cn.iocoder.yudao.module.knowledge.service.common;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库已发布内容收集器(意图总结/槽位总结共用): 文档(kbId) -> 已发布版本 -> 片段,
 * 跨版本均衡采样截断到上限
 */
@Component
public class PublishedContentCollector {

    /** 参与总结的片段上限(跨版本均衡采样, 保证多文档都有代表) */
    private static final int MAX_CHUNKS = 40;

    /** 单个片段内容截断长度(字) */
    private static final int MAX_CHUNK_LEN = 200;

    /** 单个版本最多采样的片段数(均衡采样上限) */
    private static final int MAX_CHUNKS_PER_VERSION = 15;

    @Resource
    private IngestionApi ingestionApi;
    @Resource
    private AiDocumentMapper aiDocumentMapper;
    @Resource
    private AiDocVersionMapper aiDocVersionMapper;

    /**
     * 收集知识库已发布版本片段内容(截断到上限)
     *
     * @param kbId 知识库编号
     * @return 拼接的片段内容; 无文档/无已发布版本返回空串
     */
    public String collectPublishedContent(Long kbId) {
        List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(kbId);
        if (CollUtil.isEmpty(docs)) {
            return "";
        }
        List<AiDocVersionDO> versions = aiDocVersionMapper.selectPublishedByDocIds(
                docs.stream().map(AiDocumentDO::getId).toList());
        if (CollUtil.isEmpty(versions)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chunkCount = 0;
        for (AiDocVersionDO version : versions) {
            if (chunkCount >= MAX_CHUNKS) {
                break;
            }
            List<ChunkRespDTO> chunks = ingestionApi.getChunksByVersion(version.getId()).getCheckedData();
            if (CollUtil.isEmpty(chunks)) {
                continue;
            }
            int perVersion = 0;
            for (ChunkRespDTO chunk : chunks) {
                if (chunkCount >= MAX_CHUNKS || perVersion >= MAX_CHUNKS_PER_VERSION) {
                    break;
                }
                sb.append(StrUtil.sub(StrUtil.nullToEmpty(chunk.getContent()), 0, MAX_CHUNK_LEN)).append("\n\n");
                chunkCount++;
                perVersion++;
            }
        }
        return sb.toString();
    }

}
