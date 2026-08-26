package cn.iocoder.yudao.module.ingestion.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.domain.DomainChunkingPipeline;
import cn.iocoder.yudao.module.ingestion.embedding.EmbeddingClient;
import cn.iocoder.yudao.module.ingestion.parse.ContextEnricher;
import cn.iocoder.yudao.module.ingestion.parse.DocumentParser;
import cn.iocoder.yudao.module.ingestion.parse.ImageParser;
import cn.iocoder.yudao.module.ingestion.parse.MineruParser;
import cn.iocoder.yudao.module.ingestion.parse.MineruProperties;
import cn.iocoder.yudao.module.ingestion.parse.OfficeParser;
import cn.iocoder.yudao.module.ingestion.parse.PdfParser;
import cn.iocoder.yudao.module.ingestion.parse.TextParser;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.ingestion.split.SplitUtils;
import cn.iocoder.yudao.module.ingestion.split.SplitterFactory;
import cn.iocoder.yudao.module.ingestion.store.MysqlChunkStore;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 文档入库主流程: 解析 → 领域切片 Pipeline → 向量化 → 只写 MySQL → 通知审核。
 *
 * <p>本类不再持有或选择任何具体领域 Adapter；领域元数据提取与切分由
 * {@link DomainChunkingPipeline} 统一发现和执行。</p>
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
    private ImageParser imageParser;
    @Resource
    private MineruParser mineruParser;
    @Resource
    private MineruProperties mineruProperties;
    @Resource
    private ContextEnricher contextEnricher;
    @Resource
    private cn.iocoder.yudao.module.ingestion.parse.DownloadGuard downloadGuard;
    @Resource
    private SplitterFactory splitterFactory;
    @Resource
    private EmbeddingClient embeddingClient;
    @Resource
    private MysqlChunkStore mysqlChunkStore;
    @Resource
    private KnowledgeApi knowledgeApi;
    @Resource
    private ChunkMapper chunkMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private cn.iocoder.yudao.module.ingestion.service.job.IngestionJobService ingestionJobService;
    @Resource
    private DomainChunkingPipeline domainChunkingPipeline;
    @Resource
    private cn.iocoder.yudao.module.ingestion.trace.PipelineTraceRecorder pipelineTraceRecorder;

    /** embedding 批大小(C3 分批向量化, 防大文档一次性全量) */
    @org.springframework.beans.factory.annotation.Value("${yudao.ingestion.embedding.batch-size:32}")
    private int embedBatchSize;

    /**
     * 入库主流程: 解析 → 切分 → 向量化 → 只写 MySQL(REVIEW) → 通知审核
     * <p>
     * 事务边界(P2-13): 仅"删旧片段 + 写 MySQL"在事务内(短事务, 本地操作);
     * Feign 查文档/状态回写、MinIO 下载、解析、LLM 向量化全部在事务外——
     * 避免长事务持锁拖垮 MySQL(下载/LLM 是秒级耗时, 事务内会占连接/锁)。
     * Milvus/ES 由审核通过后的发布(indexVersion)写入, 不在本方法执行。
     */
    @Override
    public void ingestDocument(Long documentId, Long jobId) {
        ingestionJobService.markRunning(jobId);
        updateStatus(documentId, "PARSING", null, null);
        try {
            ingestionJobService.updateStage(jobId, "PARSE");
            KnowledgeDocumentRespDTO document = loadDocument(documentId);
            String docType = document.getType();
            String storagePath = document.getStoragePath();
            Long kbId = document.getKbId();
            Long tenantId = document.getTenantId();
            if (tenantId == null) {
                throw new RuntimeException("文档租户不存在: " + documentId);
            }
            Long versionId = document.getCurrentVersionId();
            if (versionId == null) {
                throw new RuntimeException("文档无版本记录: " + documentId);
            }
            String chunkStrategy = document.getChunkStrategy();
            if (StrUtil.isBlank(chunkStrategy)) {
                chunkStrategy = "auto";
            }

            cn.iocoder.yudao.module.ingestion.trace.TraceContext traceContext =
                    cn.iocoder.yudao.module.ingestion.trace.TraceContext.builder()
                            .traceId(String.valueOf(documentId))
                            .kbId(kbId)
                            .documentId(documentId)
                            .versionId(versionId)
                            .jobId(jobId)
                            .domainCode(document.getDomainCode())
                            .tenantId(tenantId)
                            .build();

            java.io.File tmpFile = downloadFromMinio(storagePath, docType);
            ParsedDocument parsed;
            try {
                java.io.File finalTmp = tmpFile;
                String finalStoragePath = storagePath;
                String finalDocType = docType;
                parsed = pipelineTraceRecorder.recordStage(traceContext, "PARSE",
                        "DocumentParser:" + docType, "download:" + finalStoragePath, () -> {
                            try {
                                return parseDocument(finalDocType, finalTmp);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tmpFile.toPath());
                } catch (Exception cleanupEx) {
                    log.warn("[ingestDocument][文档 {} 临时文件清理失败: {}]", documentId, cleanupEx.getMessage());
                }
            }
            parsed.setDocName(document.getName());
            parsed.setDocType(docType);
            contextEnricher.enrich(parsed);
            ingestionJobService.updateStage(jobId, "CHUNK");

            SplitParams splitParams = SplitParams.merge(SplitParams.of(500), document.getChunkStrategyParams());
            String finalChunkStrategy = chunkStrategy;
            DomainChunkingPipeline.Result chunking = pipelineTraceRecorder.recordStage(traceContext, "CHUNK",
                    "DomainChunkingPipeline", "domain:" + StrUtil.blankToDefault(document.getDomainCode(), "GENERAL")
                            + ";strategy:" + finalChunkStrategy,
                    () -> domainChunkingPipeline.execute(parsed, splitParams, document));
            String domainMetadata = chunking.domainMetadata();
            if (domainMetadata != null) {
                CommonResult<Boolean> metaResult = knowledgeApi.updateDocumentDomainMetadata(
                        java.util.Map.of("documentId", documentId, "domainMetadata", domainMetadata));
                if (metaResult.isError() || !Boolean.TRUE.equals(metaResult.getCheckedData())) {
                    throw new RuntimeException("领域元数据持久化失败: " + metaResult.getMsg());
                }
            }
            List<Chunk> chunks = chunking.chunks();
            if (chunks.isEmpty()) {
                throw new RuntimeException("领域切片 Pipeline 未产生任何片段: plugin=" + chunking.pluginId());
            }
            List<String> contents = chunks.stream().map(Chunk::getContent).toList();
            ingestionJobService.updateStage(jobId, "EMBED");
            List<List<Float>> vectors = pipelineTraceRecorder.recordStage(traceContext, "EMBED",
                    "EmbeddingClient", "texts:" + contents.size(), () -> embedBatches(contents));

            ingestionJobService.updateStage(jobId, "PERSIST");
            persistChunks(documentId, versionId, tenantId, chunks, vectors);

            updateStatus(documentId, "REVIEW", chunks.size(), null);
            ingestionJobService.markDone(jobId, chunks.size());
            log.info("[ingestDocument][文档 {} 落库完成(REVIEW), domainPlugin={}, {} 个片段, 待事务提交后通知审核]",
                    documentId, chunking.pluginId(), chunks.size());
        } catch (Exception e) {
            log.error("[ingestDocument][文档 {} 入库失败]", documentId, e);
            ingestionJobService.markFailed(jobId, null, e.getMessage());
            try {
                updateStatus(documentId, "FAILED", null, StrUtil.sub(e.getMessage(), 0, 500));
            } catch (Exception ex) {
                log.error("[ingestDocument][文档 {} 回写 FAILED 状态失败]", documentId, ex);
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private List<List<Float>> embedBatches(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, embedBatchSize);
        List<List<Float>> vectors = new java.util.ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i += batchSize) {
            List<String> batch = contents.subList(i, Math.min(i + batchSize, contents.size()));
            List<List<Float>> batchVectors = embeddingClient.embed(batch);
            if (batchVectors == null || batchVectors.size() != batch.size()) {
                throw new RuntimeException("embedding 返回数量与输入不一致: 期望 " + batch.size() + ", 实际 "
                        + (batchVectors == null ? 0 : batchVectors.size()));
            }
            vectors.addAll(batchVectors);
        }
        return vectors;
    }

    private KnowledgeDocumentRespDTO loadDocument(Long documentId) {
        CommonResult<KnowledgeDocumentRespDTO> documentResult = knowledgeApi.getDocument(documentId);
        if (documentResult.isError()) {
            throw new ServiceException(documentResult.getCode(), documentResult.getMsg());
        }
        KnowledgeDocumentRespDTO document = documentResult.getData();
        if (document == null) {
            throw new RuntimeException("文档不存在: " + documentId);
        }
        return document;
    }

    private void persistChunks(Long documentId, Long versionId, Long tenantId,
                               List<Chunk> chunks, List<List<Float>> vectors) {
        transactionTemplate.executeWithoutResult(status -> {
            chunkMapper.deleteByVersionIdPhysical(versionId);
            java.util.List<ChunkDO> parentBatch = new java.util.ArrayList<>();
            java.util.List<Integer> parentIndexes = new java.util.ArrayList<>();
            java.util.Map<Integer, Long> indexToId = new java.util.HashMap<>();
            int seq = 0;
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (c.getParentId() == null) {
                    parentBatch.add(toChunkDO(c, versionId, tenantId, vectors.get(i), seq++));
                    parentIndexes.add(i);
                }
            }
            if (!parentBatch.isEmpty()) {
                mysqlChunkStore.insertChunks(parentBatch, tenantId);
                for (int j = 0; j < parentBatch.size(); j++) {
                    indexToId.put(parentIndexes.get(j), parentBatch.get(j).getId());
                }
            }
            java.util.List<ChunkDO> childBatch = new java.util.ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (c.getParentId() != null) {
                    ChunkDO child = toChunkDO(c, versionId, tenantId, vectors.get(i), seq++);
                    Long parentId = indexToId.get(c.getParentId().intValue());
                    child.setParentId(parentId);
                    childBatch.add(child);
                }
            }
            if (!childBatch.isEmpty()) {
                mysqlChunkStore.insertChunks(childBatch, tenantId);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        CommonResult<Boolean> notifyResult = knowledgeApi.notifyParsed(documentId, versionId);
                        if (notifyResult.isError()) {
                            log.error("[ingestDocument][文档 {} 通知审核失败: {}]", documentId, notifyResult.getMsg());
                        }
                    } catch (Exception e) {
                        log.error("[ingestDocument][文档 {} 通知审核异常]", documentId, e);
                    }
                }
            });
        });
    }

    private ChunkDO toChunkDO(Chunk c, Long versionId, Long tenantId, List<Float> vector, int seq) {
        ChunkDO chunkDO = new ChunkDO();
        chunkDO.setVersionId(versionId);
        chunkDO.setContent(c.getContent());
        chunkDO.setChunkType(c.getChunkType());
        chunkDO.setStatus(cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum.REVIEW.getStatus());
        chunkDO.setMetadata(c.getMetadata());
        chunkDO.setEmbedding(cn.hutool.json.JSONUtil.toJsonStr(vector));
        chunkDO.setChunkKey(String.format("c%06d", seq));
        chunkDO.setChunkSeq(seq);
        chunkDO.setChunkRole(c.getChunkRole() != null ? c.getChunkRole()
                : ("TABLE".equals(c.getChunkType()) ? "TABLE" : "IMAGE".equals(c.getChunkType()) ? "IMAGE" : "LEAF"));
        chunkDO.setSectionPath(c.getSectionPath());
        chunkDO.setSourcePageStart(c.getSourcePageStart() <= 0 ? -1 : c.getSourcePageStart());
        chunkDO.setSourcePageEnd(c.getSourcePageEnd() <= 0 ? -1 : c.getSourcePageEnd());
        chunkDO.setTokenCount(SplitUtils.estimateTokens(c.getContent()));
        chunkDO.setContentHash(cn.hutool.crypto.SecureUtil.sha256(c.getContent()));
        return chunkDO;
    }

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
            case "IMAGE" -> imageParser;
            default -> textParser;
        };
    }

    private ParsedDocument parseDocument(String docType, java.io.File tmpFile) throws Exception {
        if ("PDF".equalsIgnoreCase(docType) && mineruProperties.isEnabled()) {
            try {
                return mineruParser.parseStructured(tmpFile.getAbsolutePath(), docType);
            } catch (Exception e) {
                log.warn("[parseDocument][MinerU 解析失败, 降级 PDFBox: {}]", StrUtil.sub(e.getMessage(), 0, 300));
            }
        }
        return chooseParser(docType).parseStructured(tmpFile.getAbsolutePath(), docType);
    }

    private java.io.File downloadFromMinio(String storagePath, String docType) {
        downloadGuard.validateUrl(storagePath);
        String fileName = StrUtil.subAfter(storagePath, "/", true);
        String tmpName = java.util.UUID.randomUUID() + "_" + fileName;
        java.io.File tmpFile = new java.io.File(System.getProperty("java.io.tmpdir"), tmpName);
        try {
            downloadGuard.download(storagePath, tmpFile);
            downloadGuard.validateMagic(tmpFile, docType);
        } catch (RuntimeException e) {
            try {
                java.nio.file.Files.deleteIfExists(tmpFile.toPath());
            } catch (Exception ignored) {
            }
            throw e;
        }
        return tmpFile;
    }

}
