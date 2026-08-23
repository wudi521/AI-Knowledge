package cn.iocoder.yudao.module.ingestion.service;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import cn.iocoder.yudao.module.ingestion.embedding.EmbeddingClient;
import cn.iocoder.yudao.module.ingestion.parse.ContextEnricher;
import cn.iocoder.yudao.module.ingestion.parse.DocumentParser;
import cn.iocoder.yudao.module.ingestion.parse.ImageParser;
import cn.iocoder.yudao.module.ingestion.parse.MineruParser;
import cn.iocoder.yudao.module.ingestion.parse.MineruProperties;
import cn.iocoder.yudao.module.ingestion.parse.OfficeParser;
import cn.iocoder.yudao.module.ingestion.parse.PdfParser;
import cn.iocoder.yudao.module.ingestion.parse.TextParser;
import cn.iocoder.yudao.module.ingestion.domain.DomainIngestionAdapter;
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
 * 文档入库主流程: 解析 → 切分 → 向量化 → 只写 MySQL → 通知审核
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
    private cn.iocoder.yudao.module.ingestion.domain.DomainIngestionRegistry domainRegistry;
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
        // C3 任务状态机: 开始执行
        ingestionJobService.markRunning(jobId);
        // 1. 置为解析中(事务外: Feign 回写)
        updateStatus(documentId, "PARSING", null, null);
        try {
            ingestionJobService.updateStage(jobId, "PARSE");
            // 2. 查询文档信息 + 解析 + 切分 + 向量化(全部事务外, 耗时步骤)
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
                chunkStrategy = "auto"; // 文档未选策略默认自动
            }

            // Knowledge Ops Trace 上下文(阶段级记录)
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
                // 闭包使用下载后的本地临时文件(非 MinIO URL); inputSummary 用 URL 便于排障
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
                // P2-14: 临时文件必须清理(失败也删, 不留垃圾)
                try {
                    java.nio.file.Files.deleteIfExists(tmpFile.toPath());
                } catch (Exception cleanupEx) {
                    log.warn("[ingestDocument][文档 {} 临时文件清理失败: {}]", documentId, cleanupEx.getMessage());
                }
            }
            parsed.setDocName(document.getName());
            parsed.setDocType(docType);
            // 上下文增强(切分前): 标题链回填 + 图片理解(描述生成) + 图片上下文绑定
            contextEnricher.enrich(parsed);
            ingestionJobService.updateStage(jobId, "CHUNK");
            // 领域接入(Batch B): 按知识库 domainCode 路由领域适配器(未找到回退 GENERAL)
            DomainIngestionAdapter domainAdapter = domainRegistry.get(document.getDomainCode());
            String domainMetadata = domainAdapter.extractMetadata(parsed, document);
            if (domainMetadata != null) {
                // 持久化领域元数据(失败即入库失败, 不允许元数据与 Chunk 静默不一致)
                CommonResult<Boolean> metaResult = knowledgeApi.updateDocumentDomainMetadata(
                        java.util.Map.of("documentId", documentId, "domainMetadata", domainMetadata));
                if (metaResult.isError() || !Boolean.TRUE.equals(metaResult.getCheckedData())) {
                    throw new RuntimeException("领域元数据持久化失败: " + metaResult.getMsg());
                }
            }
            SplitParams splitParams = SplitParams.merge(SplitParams.of(500), document.getChunkStrategyParams());
            List<Chunk> chunks = pipelineTraceRecorder.recordStage(traceContext, "CHUNK",
                    domainAdapter.getClass().getSimpleName(), "strategy:" + chunkStrategy,
                    () -> domainAdapter.split(parsed, splitParams, domainMetadata));
            List<String> contents = chunks.stream().map(Chunk::getContent).toList();
            // C3 分批向量化(批大小可配, 默认 32; 防大文档一次性全量占满 JVM/模型请求)
            ingestionJobService.updateStage(jobId, "EMBED");
            List<List<Float>> vectors = pipelineTraceRecorder.recordStage(traceContext, "EMBED",
                    "EmbeddingClient", "texts:" + contents.size(), () -> embedBatches(contents));

            // 3. 短事务: 删旧片段 + 只写 MySQL(向量存 embedding; Milvus/ES 待审核通过发布时写)
            //    通知审核的 afterCommit 注册在事务内(见 persistChunks), 保证事务提交后触发
            ingestionJobService.updateStage(jobId, "PERSIST");
            persistChunks(documentId, versionId, tenantId, chunks, vectors);

            // 4. 置为 REVIEW(事务外 Feign 回写)
            updateStatus(documentId, "REVIEW", chunks.size(), null);
            ingestionJobService.markDone(jobId, chunks.size());
            log.info("[ingestDocument][文档 {} 落库完成(REVIEW), {} 个片段, 待事务提交后通知审核]", documentId, chunks.size());
        } catch (Exception e) {
            log.error("[ingestDocument][文档 {} 入库失败]", documentId, e);
            ingestionJobService.markFailed(jobId, null, e.getMessage()); // C3 任务置 FAILED(可重试)
            try {
                updateStatus(documentId, "FAILED", null, StrUtil.sub(e.getMessage(), 0, 500));
            } catch (Exception ex) {
                log.error("[ingestDocument][文档 {} 回写 FAILED 状态失败]", documentId, ex);
            }
            // 异常继续传播: 触发 Kafka 重投(事务外无回滚需求; 短事务内失败已自行回滚)
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 分批向量化(C3): 批大小可配(默认 32), 防大文档一次性全量占满内存/模型请求;
     * 校验向量数量与输入一致(模型返回缺失直接报错, 不静默错位落库)
     */
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

    /**
     * 查询文档信息(Feign, 事务外)
     */
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

    /**
     * 短事务: 删旧片段 + 批量写 MySQL + 注册 afterCommit 通知审核
     * <p>
     * P2-13: 仅本地 DB 操作在事务内(快速提交, 不持锁);
     * 通知审核必须在事务提交后(afterCommit)调用——knowledge 会回读本版本 chunk(新连接新事务),
     * 事务内调用会读到 0 行 -> 空抽取 -> 误判"无必审条目"自动发布空版本。
     */
    private void persistChunks(Long documentId, Long versionId, Long tenantId,
                               List<Chunk> chunks, List<List<Float>> vectors) {
        transactionTemplate.executeWithoutResult(status -> {
            // 1. 清理旧片段(按真实版本 id, 幂等)
            chunkMapper.deleteByVersionId(versionId);
            // 2. 只写 MySQL(REVIEW 状态, 向量存 embedding): 两阶段批量插入(B2)
            //    阶段1 批量插 父块/叶子(parentId=null), 记录 列表下标→DB id;
            //    阶段2 批量插 子块, parentId(下标) 回填真实 DB id。禁止逐条 insert。
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
                mysqlChunkStore.insertChunks(parentBatch, tenantId); // 批量(insertBatch 回填自增 id)
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
                    child.setParentId(parentId); // 真实父块 DB id(跨版本不串: 同版本内回填)
                    childBatch.add(child);
                }
            }
            if (!childBatch.isEmpty()) {
                mysqlChunkStore.insertChunks(childBatch, tenantId); // 批量
            }
            // 3. 事务内注册 afterCommit(此时有活跃事务同步; 事务外注册会抛 IllegalStateException)
            //    必须传管线实际使用的 versionId(不能由 knowledge 按最新推断)
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 失败只记日志不抛: 抛错会让 Kafka 重投本消息 -> 重复插 chunk(无幂等兜底)!
                        // 恢复路径: 文档保持 REVIEW/FAILED, 前端"重试抽取"(retry-extract)可重新触发
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

    /** Chunk → ChunkDO(填充版本/状态/向量/可追溯元数据: chunkKey/seq/role/页码/hash/token) */
    private ChunkDO toChunkDO(Chunk c, Long versionId, Long tenantId, List<Float> vector, int seq) {
        ChunkDO chunkDO = new ChunkDO();
        chunkDO.setVersionId(versionId); // 真实版本 id(版本状态机)
        chunkDO.setContent(c.getContent());
        chunkDO.setChunkType(c.getChunkType());
        chunkDO.setStatus(cn.iocoder.yudao.module.ingestion.enums.ChunkStatusEnum.REVIEW.getStatus()); // 待审核, 发布时置 PUBLISHED
        chunkDO.setMetadata(c.getMetadata());
        chunkDO.setEmbedding(cn.hutool.json.JSONUtil.toJsonStr(vector));
        // B2 可追溯元数据
        chunkDO.setChunkKey(String.format("c%06d", seq)); // 版本内稳定唯一业务键
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
            case "IMAGE" -> imageParser;
            default -> textParser; // TXT / MD
        };
    }

    /**
     * 结构化解析: PDF 且启用 MinerU 时优先走 MinerU(中文布局感知), 失败降级 PDFBox; 其余按类型解析。
     */
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

    /**
     * 从 MinIO 下载文档到临时文件(P2-14): UUID 文件名防并发冲突, 带超时, 调用方 finally 清理。
     *
     * @return 临时文件(调用方负责删除)
     */
    private java.io.File downloadFromMinio(String storagePath, String docType) {
        // A3 安全防护: 下载源白名单(SSRF) + 大小限制 + magic number 类型校验
        downloadGuard.validateUrl(storagePath);
        // MinIO URL 形如 http://127.0.0.1:9000/kb-docs/xxx
        String fileName = StrUtil.subAfter(storagePath, "/", true);
        // UUID 前缀防多文档并发下载同名文件互相覆盖; 保留原扩展名供解析器识别
        String tmpName = java.util.UUID.randomUUID() + "_" + fileName;
        java.io.File tmpFile = new java.io.File(System.getProperty("java.io.tmpdir"), tmpName);
        try {
            downloadGuard.download(storagePath, tmpFile);
            downloadGuard.validateMagic(tmpFile, docType);
        } catch (RuntimeException e) {
            // 校验/下载失败: 清理临时文件再抛(不留垃圾)
            try {
                java.nio.file.Files.deleteIfExists(tmpFile.toPath());
            } catch (Exception ignored) {
            }
            throw e;
        }
        return tmpFile;
    }

}
