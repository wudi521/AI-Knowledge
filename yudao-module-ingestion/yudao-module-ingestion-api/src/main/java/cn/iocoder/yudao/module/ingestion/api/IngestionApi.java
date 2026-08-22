package cn.iocoder.yudao.module.ingestion.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
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
     * 按版本移除检索索引(版本过期/回滚时调用): MySQL chunk 置 DISABLED(保留历史审计) + ES/Milvus 删除。
     * 保证旧版本内容不再参与检索(版本 → chunk → 索引 失效链闭环)。
     *
     * @param versionId 版本编号
     * @return 是否成功(ES/Milvus 失败仅告警不返回错误, 残留待清理)
     */
    @PostMapping(ApiConstants.PREFIX + "/delete-version-index")
    CommonResult<Boolean> deleteVersionIndex(@RequestParam("versionId") Long versionId);

    /**
     * 发布索引: 按版本从 MySQL 读 chunk + embedding, 写 Milvus/ES, 置 chunk PUBLISHED
     * <p>
     * 幂等契约(发布可重试): 同版本重复调用为"覆盖式"重写 Milvus/ES, 不产生重复数据;
     * "置 chunk PUBLISHED"必须是最后一步; 任一中间失败可安全重试。
     *
     * @param versionId 版本编号(ai_chunk.version_id)
     * @param kbId 知识库编号
     * @param tenantId 租户编号
     * @param documentId 文档编号(C6 索引标量, 供文档级过滤/审计)
     * @return 是否成功
     */
    @PostMapping(ApiConstants.PREFIX + "/index-version")
    CommonResult<Boolean> indexVersion(@RequestParam("versionId") Long versionId,
                                       @RequestParam("kbId") Long kbId,
                                       @RequestParam("tenantId") Long tenantId,
                                       @RequestParam("documentId") Long documentId);

    /**
     * 按版本查询片段列表(供 knowledge 抽取审核条目)
     *
     * @param versionId 版本编号
     * @return 片段列表
     */
    @GetMapping(ApiConstants.PREFIX + "/get-chunks-by-version")
    CommonResult<List<ChunkRespDTO>> getChunksByVersion(@RequestParam("versionId") Long versionId);

    /**
     * 校验版本是否存在未发布(非 PUBLISHED)的片段
     * <p>
     * 供知识发布幂等分支使用: 版本已 PUBLISHED 但片段被重新抽取(新片段默认 REVIEW)时,
     * 需重跑 indexVersion 同步索引; 无未发布片段则正常幂等跳过。
     *
     * @param versionId 版本编号
     * @return 是否存在未发布片段
     */
    @GetMapping(ApiConstants.PREFIX + "/has-unpublished-chunks")
    CommonResult<Boolean> hasUnpublishedChunks(@RequestParam("versionId") Long versionId);

    /**
     * 批量查询 chunk 是否已发布(检索结果状态过滤用)
     *
     * @param chunkIds 片段编号列表
     * @return chunkId -> 是否 PUBLISHED
     */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-publish-map")
    CommonResult<Map<Long, Boolean>> getChunkPublishMap(@RequestBody List<Long> chunkIds);

    /**
     * 批量查询 chunk 内容(检索结果展示用)
     *
     * @param chunkIds 片段编号列表
     * @return chunkId -> 片段内容
     */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-contents")
    CommonResult<Map<Long, String>> getChunkContents(@RequestBody List<Long> chunkIds);

    /**
     * 批量查询 chunk 元数据(专利来源卡片: applicationNo/publicationNo/sectionType/claimNo 等)
     *
     * @param chunkIds 片段编号列表
     * @return chunkId -> metadata JSON(无则空串)
     */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-metadatas")
    CommonResult<Map<Long, String>> getChunkMetadatas(@RequestBody List<Long> chunkIds);

    /**
     * 批量查询 chunk 的父块编号(检索父子扩展: 子块命中回带父块上下文)
     *
     * @param chunkIds 片段编号列表
     * @return chunkId -> parentId(无父块的不在返回中)
     */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-parents")
    CommonResult<Map<Long, Long>> getChunkParents(@RequestBody List<Long> chunkIds);

    /**
     * 批量查询片段所属文档信息(检索结果补全: 文档名/版本号)
     *
     * @param chunkIds 片段编号列表
     * @return chunkId -> 文档信息(documentId/documentName/versionNo)
     */
    @PostMapping(ApiConstants.PREFIX + "/get-chunk-doc-info")
    CommonResult<Map<Long, ChunkDocInfoDTO>> getChunkDocInfo(@RequestBody List<Long> chunkIds);

}
