package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgePublishedChunkDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeSlotDefinitionDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import feign.FeignIgnore;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 knowledge-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface KnowledgeApi {

    @FeignIgnore
    Boolean checkKnowledgePermission(Long chunkId, Long userId);

    @PostMapping(ApiConstants.PREFIX + "/update-parse-status")
    CommonResult<Boolean> updateDocumentParseStatus(@RequestParam("documentId") Long documentId,
                                                     @RequestParam("parseStatus") String parseStatus,
                                                     @RequestParam(value = "chunkCount", required = false) Integer chunkCount,
                                                     @RequestParam(value = "errorMsg", required = false) String errorMsg);

    @GetMapping(ApiConstants.PREFIX + "/get-document")
    CommonResult<KnowledgeDocumentRespDTO> getDocument(@RequestParam("id") Long id);

    @PostMapping(ApiConstants.PREFIX + "/notify-parsed")
    CommonResult<Boolean> notifyParsed(@RequestParam("documentId") Long documentId,
                                       @RequestParam("versionId") Long versionId);

    @GetMapping(ApiConstants.PREFIX + "/get-doc-version-ids")
    CommonResult<List<Long>> getDocVersionIds(@RequestParam("docId") Long docId);

    @PostMapping(ApiConstants.PREFIX + "/get-version-map")
    CommonResult<Map<Long, KnowledgeVersionRespDTO>> getVersionMap(@RequestBody List<Long> versionIds);

    @PostMapping(ApiConstants.PREFIX + "/get-document-map")
    CommonResult<Map<Long, KnowledgeDocumentRespDTO>> getDocumentMap(@RequestBody List<Long> ids);

    @GetMapping(ApiConstants.PREFIX + "/get-visible-kb-ids")
    CommonResult<Set<Long>> getVisibleKbIds(@RequestParam("userId") Long userId);

    @PostMapping(ApiConstants.PREFIX + "/update-document-domain-metadata")
    CommonResult<Boolean> updateDocumentDomainMetadata(@RequestBody Map<String, Object> body);

    @PostMapping(ApiConstants.PREFIX + "/get-kb-domain-codes")
    CommonResult<Map<Long, String>> getKbDomainCodes(@RequestBody List<Long> kbIds);

    /**
     * 在已完成权限裁剪的知识库范围内按申请号/公布号精确定位专利文档。
     * 返回 0..N 个 documentId；正常数据应唯一，重复数据由检索侧继续保守过滤。
     */
    @PostMapping(ApiConstants.PREFIX + "/lookup-patent-documents")
    CommonResult<List<Long>> lookupPatentDocuments(@RequestBody PatentDocumentLookupReqDTO req);

    @PostMapping(ApiConstants.PREFIX + "/get-kb-scopes")
    CommonResult<Map<Long, List<KnowledgeScopeDTO>>> getKbScopes(@RequestBody List<Long> kbIds);

    @GetMapping(ApiConstants.PREFIX + "/intent/list-by-kb")
    CommonResult<List<IntentDTO>> getKbIntents(@RequestParam("kbId") Long kbId);

    @PostMapping(ApiConstants.PREFIX + "/get-kb-slots")
    CommonResult<List<KnowledgeSlotDefinitionDTO>> getSlotDefinitions(@RequestBody List<Long> kbIds);

    @GetMapping(ApiConstants.PREFIX + "/get-published-chunks")
    CommonResult<List<KnowledgePublishedChunkDTO>> getPublishedChunks(@RequestParam("kbId") Long kbId);

    /** P0-10/AG-03: 知识库聚合统计(确定性计数; metric=DOCUMENT_COUNT/PATENT_COUNT/KNOWLEDGE_ENTRY_COUNT) */
    @GetMapping(ApiConstants.PREFIX + "/aggregate-count")
    CommonResult<Integer> aggregateCount(@RequestParam("kbId") Long kbId,
                                         @RequestParam("metric") String metric,
                                         @RequestParam(value = "publishedOnly", required = false) Boolean publishedOnly,
                                         @RequestParam(value = "domainCode", required = false) String domainCode);

}
