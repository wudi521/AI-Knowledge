package cn.iocoder.yudao.module.ingestion.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.embedding.EmbeddingClient;
import cn.iocoder.yudao.module.ingestion.parse.DocumentParser;
import cn.iocoder.yudao.module.ingestion.parse.OfficeParser;
import cn.iocoder.yudao.module.ingestion.parse.PdfParser;
import cn.iocoder.yudao.module.ingestion.parse.TextParser;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.SplitterFactory;
import cn.iocoder.yudao.module.ingestion.store.EsChunkStore;
import cn.iocoder.yudao.module.ingestion.store.MilvusChunkStore;
import cn.iocoder.yudao.module.ingestion.store.MysqlChunkStore;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库主流程: 解析 → 切分 → 向量化 → 三写
 */
@Slf4j
@Service
public class IngestServiceImpl implements IngestService {

    @Resource
    private TextParser textParser;
    @Resource
    private PdfParser pdfParser;
    @Resource
    private OfficeParser officeParser;
    @Resource
    private SplitterFactory splitterFactory;
    @Resource
    private EmbeddingClient embeddingClient;
    @Resource
    private MysqlChunkStore mysqlChunkStore;
    @Resource
    private MilvusChunkStore milvusChunkStore;
    @Resource
    private EsChunkStore esChunkStore;
    @Resource
    private KnowledgeApi knowledgeApi;

    @Override
    public void ingestDocument(Long documentId) {
        // 1. 置为解析中
        knowledgeApi.updateDocumentParseStatus(documentId, "PARSING", null, null);
        try {
            // 2. 查询文档信息(占位: Task 10 接入文档详情 Feign 后替换)
            // TODO: 从 knowledge-server 取文档详情(类型/存储路径/知识库/租户/切分策略)
            String docType = "TXT";          // 占位
            String storagePath = "";         // 占位(MinIO URL)
            Long kbId = 1L;                  // 占位
            Long tenantId = 1L;              // 占位
            String chunkStrategy = "ParentChild"; // 占位

            // 3. 解析
            String filePath = downloadFromMinio(storagePath);
            DocumentParser parser = chooseParser(docType);
            String text = parser.parse(filePath, docType);

            // 4. 切分
            List<Chunk> chunks = splitterFactory.getSplitter(chunkStrategy)
                    .split(text, 500);

            // 5. 向量化(批量)
            List<String> contents = chunks.stream().map(Chunk::getContent).toList();
            List<List<Float>> vectors = embeddingClient.embed(contents);

            // 6. 三写: 先 MySQL, 再 Milvus, 再 ES
            List<ChunkDO> dos = new ArrayList<>();
            List<Long> chunkIds = new ArrayList<>();
            List<List<Float>> validVectors = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkDO chunkDO = new ChunkDO();
                chunkDO.setVersionId(documentId); // 版本状态机后续接入, 暂用文档 id
                chunkDO.setContent(chunks.get(i).getContent());
                chunkDO.setChunkType(chunks.get(i).getChunkType());
                chunkDO.setStatus("PUBLISHED");
                chunkDO.setMetadata(chunks.get(i).getMetadata());
                chunkDO.setParentId(chunks.get(i).getParentId());
                mysqlChunkStore.insertChunks(List.of(chunkDO));
                chunkDO.setVectorKey(String.valueOf(chunkDO.getId()));
                chunkIds.add(chunkDO.getId());
                validVectors.add(vectors.get(i));
                // ES 写入
                esChunkStore.insertChunk(chunkDO.getId(), tenantId, kbId, chunkDO.getContent());
            }
            // Milvus 批量写
            milvusChunkStore.insertVectors(chunkIds, validVectors, tenantId, kbId);

            // 7. 置为已入库(带回片段数)
            knowledgeApi.updateDocumentParseStatus(documentId, "INDEXED", chunks.size(), null);
            log.info("[ingestDocument][文档 {} 入库完成, {} 个片段]", documentId, chunks.size());
        } catch (Exception e) {
            log.error("[ingestDocument][文档 {} 入库失败]", documentId, e);
            knowledgeApi.updateDocumentParseStatus(documentId, "FAILED", null, StrUtil.sub(e.getMessage(), 0, 500));
        }
    }

    private DocumentParser chooseParser(String docType) {
        return switch (docType) {
            case "PDF" -> pdfParser;
            case "WORD", "EXCEL", "PPT" -> officeParser;
            default -> textParser; // TXT / MD
        };
    }

    private String downloadFromMinio(String storagePath) {
        // TODO: 从 MinIO 下载(storagePath 为 URL); Task 11 接入
        throw new UnsupportedOperationException("MinIO 下载待接入");
    }

}
