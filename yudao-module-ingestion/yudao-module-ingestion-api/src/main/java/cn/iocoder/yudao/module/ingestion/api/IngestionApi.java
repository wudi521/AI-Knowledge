package cn.iocoder.yudao.module.ingestion.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.IngestionJobTraceDTO;
import cn.iocoder.yudao.module.ingestion.enums.ApiConstants;
import feign.FeignIgnore;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
/**
 * 入库管线 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 ingestion-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface IngestionApi {

    /** 占位方法: 按领域替换为真实接口 */
    @FeignIgnore
    Boolean triggerIngest(Long documentId);

    /**
     * 删除文档关联的全部数据(MySQL ai_chunk + ES 索引 + Milvus 向量)
     *
     * @param documentId 文档编号(ai_chunk.version_id)
     * @return 是否成功
     */
    @PostMapping(ApiConstants.PREFIX + "/delete-document-data")
    CommonResult<Boolean> deleteDocumentData(@RequestParam("documentId") Long documentId);

    /**
     * 查询文档入库任务 Trace(Knowledge Ops Document Trace: job + 阶段时间轴)
     */
    @GetMapping(ApiConstants.PREFIX + "/get-ingestion-job-trace")
    CommonResult<IngestionJobTraceDTO> getIngestionJobTrace(@RequestParam("documentId") Long documentId);

    /** 按版本移除检索索引。 */
    @PostMapping(ApiConstants.PREFIX + "/delete-version-index")
    CommonResult<Boolean> deleteVersionIndex(@RequestParam("versionId") Long versionId);

    /** 发布索引。 */
    @PostMapping(ApiConstants.PREFIX + "/index-version")
    CommonResult<Boolean> indexVersion(@RequestParam("versionId") Long versionId,
                                       @RequestParam("kbId") Long kbId,
                                       @RequestParam("tenantId") Long tenantId,
                                       @RequestParam("documentId") Long documentId);

    /** 按版本查询片段列表。 */
    @GetMapping(ApiConstants.PREFIX + "/get-chunks-by-version")
    CommonResult<List<ChunkRespDTO>> getChunksByVersion(@RequestParam("versionId") Long versionId);

    /** 校验版本是否存在未发布片段。 */
    @GetMapping(ApiConstants.PREFIX + "/has-unpublished-chunks")
    CommonResult<Boolean> hasUnpublishedChunks(@RequestParam("versionId") Long versionId);

    /** 批量查询 chunk 是否已发布。 */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-publish-map")
    CommonResult<Map<Long, Boolean>> getChunkPublishMap(@RequestBody List<Long> chunkIds);

    /** 批量查询 chunk 内容。 */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-contents")
    CommonResult<Map<Long, String>> getChunkContents(@RequestBody List<Long> chunkIds);

    /** 批量查询 chunk 元数据。 */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-metadatas")
    CommonResult<Map<Long, String>> getChunkMetadatas(@RequestBody List<Long> chunkIds);

    /** 批量查询 chunk 父块编号。 */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-parents")
    CommonResult<Map<Long, Long>> getChunkParents(@RequestBody List<Long> chunkIds);

    /** 批量查询片段所属文档信息。 */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-doc-info")
    CommonResult<Map<Long, ChunkDocInfoDTO>> getChunkDocInfo(@RequestBody List<Long> chunkIds);

}
