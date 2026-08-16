package cn.iocoder.yudao.module.ingestion.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
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
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Resource
    private ChunkMapper chunkMapper;

    /**
     * 入库主流程: 解析 → 切分 → 向量化 → 三写
     * <p>
     * MySQL 写入处于同一事务: 中途失败回滚已插行; FAILED 状态回写是 Feign 远程调用,
     * 在 catch 中执行, 不受本地事务回滚影响
     */
    @Override
    @Transactional
    public void ingestDocument(Long documentId) {
        // 1. 置为解析中
        updateStatus(documentId, "PARSING", null, null);
        try {
            // 2. 查询文档信息
            CommonResult<KnowledgeDocumentRespDTO> documentResult = knowledgeApi.getDocument(documentId);
            if (documentResult.isError()) {
                throw new ServiceException(documentResult.getCode(), documentResult.getMsg());
            }
            KnowledgeDocumentRespDTO document = documentResult.getData();
            if (document == null) {
                throw new RuntimeException("文档不存在: " + documentId);
            }
            String docType = document.getType();
            String storagePath = document.getStoragePath();
            Long kbId = document.getKbId();
            Long tenantId = document.getTenantId();
            if (tenantId == null) {
                throw new RuntimeException("文档租户不存在: " + documentId);
            }
            String chunkStrategy = getKnowledgeBaseStrategy(kbId);

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

            // 6. 三写前清理旧片段: 重试/重发时按 versionId 删除残留, 保证幂等(ai_chunk 无唯一约束)
            chunkMapper.deleteByVersionId(documentId);

            // 7. 三写: 先 MySQL, 再 Milvus, 再 ES
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
                mysqlChunkStore.insertChunks(List.of(chunkDO), tenantId);
                chunkDO.setVectorKey(String.valueOf(chunkDO.getId()));
                chunkIds.add(chunkDO.getId());
                validVectors.add(vectors.get(i));
                // ES 写入
                esChunkStore.insertChunk(chunkDO.getId(), tenantId, kbId, chunkDO.getContent());
            }
            // Milvus 批量写
            milvusChunkStore.insertVectors(chunkIds, validVectors, tenantId, kbId);

            // 8. 置为已入库(带回片段数)
            updateStatus(documentId, "INDEXED", chunks.size(), null);
            log.info("[ingestDocument][文档 {} 入库完成, {} 个片段]", documentId, chunks.size());
        } catch (Exception e) {
            log.error("[ingestDocument][文档 {} 入库失败]", documentId, e);
            try {
                updateStatus(documentId, "FAILED", null, StrUtil.sub(e.getMessage(), 0, 500));
            } catch (Exception ex) {
                log.error("[ingestDocument][文档 {} 回写 FAILED 状态失败]", documentId, ex);
            }
            // 异常继续传播: 触发 @Transactional 回滚 MySQL 已插行, 并交由 Kafka 重投
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 回写文档解析状态; 回写失败抛业务异常(芋道惯例: 检查 CommonResult)
     */
    private void updateStatus(Long documentId, String parseStatus, Integer chunkCount, String errorMsg) {
        CommonResult<Boolean> result = knowledgeApi.updateDocumentParseStatus(documentId, parseStatus, chunkCount, errorMsg);
        if (result.isError()) {
            throw new ServiceException(result.getCode(), result.getMsg());
        }
    }

    private DocumentParser chooseParser(String docType) {
        return switch (docType) {
            case "PDF" -> pdfParser;
            case "WORD", "EXCEL", "PPT" -> officeParser;
            default -> textParser; // TXT / MD
        };
    }

    private String getKnowledgeBaseStrategy(Long kbId) {
        // TODO: 知识库详情 Feign 后续接入; 先返回默认 ParentChild
        return "ParentChild";
    }

    private String downloadFromMinio(String storagePath) {
        if (StrUtil.isBlank(storagePath)) {
            throw new RuntimeException("存储路径为空");
        }
        // MinIO URL 形如 http://127.0.0.1:9000/kb-docs/xxx
        String fileName = StrUtil.subAfter(storagePath, "/", true);
        String tmpFile = System.getProperty("java.io.tmpdir") + "/" + fileName;
        cn.hutool.http.HttpUtil.downloadFile(storagePath, tmpFile);
        return tmpFile;
    }

}
