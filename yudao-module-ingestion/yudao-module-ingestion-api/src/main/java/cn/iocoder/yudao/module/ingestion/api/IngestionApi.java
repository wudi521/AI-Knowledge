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

    @PostMapping(ApiConstants.PREFIX + "/delete-document-data")
    CommonResult<Boolean> deleteDocumentData(@RequestParam("documentId") Long documentId);

    @GetMapping(ApiConstants.PREFIX + "/get-ingestion-job-trace")
    CommonResult<IngestionJobTraceDTO> getIngestionJobTrace(@RequestParam("documentId") Long documentId);

    @PostMapping(ApiConstants.PREFIX + "/delete-version-index")
    CommonResult<Boolean> deleteVersionIndex(@RequestParam("versionId") Long versionId);

    @PostMapping(ApiConstants.PREFIX + "/index-version")
    CommonResult<Boolean> indexVersion(@RequestParam("versionId") Long versionId,
                                       @RequestParam("kbId") Long kbId,
                                       @RequestParam("tenantId") Long tenantId,
                                       @RequestParam("documentId") Long documentId);

    @GetMapping(ApiConstants.PREFIX + "/get-chunks-by-version")
    CommonResult<List<ChunkRespDTO>> getChunksByVersion(@RequestParam("versionId") Long versionId);

    @GetMapping(ApiConstants.PREFIX + "/has-unpublished-chunks")
    CommonResult<Boolean> hasUnpublishedChunks(@RequestParam("versionId") Long versionId);

    @PostMapping(ApiConstants.PREFIX + "/get-chunk-publish-map")
    CommonResult<Map<Long, Boolean>> getChunkPublishMap(@RequestBody List<Long> chunkIds);

    @PostMapping(ApiConstants.PREFIX + "/get-chunk-contents")
    CommonResult<Map<Long, String>> getChunkContents(@RequestBody List<Long> chunkIds);

    @PostMapping(ApiConstants.PREFIX + "/get-chunk-metadatas")
    CommonResult<Map<Long, String>> getChunkMetadatas(@RequestBody List<Long> chunkIds);

    @PostMapping(ApiConstants.PREFIX + "/get-chunk-parents")
    CommonResult<Map<Long, Long>> getChunkParents(@RequestBody List<Long> chunkIds);

    @PostMapping(ApiConstants.PREFIX + "/get-chunk-doc-info")
    CommonResult<Map<Long, ChunkDocInfoDTO>> getChunkDocInfo(@RequestBody List<Long> chunkIds);

}
