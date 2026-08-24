package cn.iocoder.yudao.module.knowledge.api;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.api.dto.DocumentVisibilityReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgePublishedChunkDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeSlotDefinitionDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.enums.version.VersionStatusEnum;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.common.PublishedContentCollector;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiKnowledgeBaseSlotService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.KnowledgePermissionHelper;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 知识平台 对外 RPC 实现 */
@Slf4j
@RestController
@Validated
public class KnowledgeApiImpl implements KnowledgeApi {

    @Resource private AiDocumentService aiDocumentService;
    @Resource private AiDocVersionService aiDocVersionService;
    @Resource private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;
    @Resource private cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper aiDocumentMapper;
    @Resource private cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper aiDocVersionMapper;
    @Resource private KnowledgePermissionHelper knowledgePermissionHelper;
    @Resource private IntentService intentService;
    @Resource private AiKnowledgeBaseSlotService aiKnowledgeBaseSlotService;
    @Resource private PublishedContentCollector publishedContentCollector;
    @Resource private cn.iocoder.yudao.module.knowledge.dal.mysql.scope.AiKnowledgeScopeMapper aiKnowledgeScopeMapper;

    @Override
    public Boolean checkKnowledgePermission(Long chunkId, Long userId) { return true; }

    @Override
    public CommonResult<Boolean> updateDocumentParseStatus(Long documentId, String parseStatus,
                                                           Integer chunkCount, String errorMsg) {
        aiDocumentService.updateParseStatus(documentId, parseStatus, chunkCount, errorMsg);
        return success(true);
    }

    @Override
    public CommonResult<KnowledgeDocumentRespDTO> getDocument(Long id) {
        AiDocumentDO doc = aiDocumentService.getAiDocument(id);
        if (doc == null) return success(null);
        KnowledgeDocumentRespDTO dto = toDocumentDto(doc, true);
        AiDocVersionDO version = aiDocVersionService.getLatestVersion(doc.getId());
        dto.setCurrentVersionId(version == null ? null : version.getId());
        return success(dto);
    }

    @Override
    public CommonResult<Boolean> notifyParsed(Long documentId, Long versionId) {
        aiDocumentService.notifyParsed(documentId, versionId);
        return success(true);
    }

    @Override
    public CommonResult<Map<Long, KnowledgeDocumentRespDTO>> getDocumentMap(List<Long> ids) {
        Map<Long, KnowledgeDocumentRespDTO> map = new HashMap<>();
        if (CollUtil.isEmpty(ids)) return success(map);
        for (AiDocumentDO doc : aiDocumentMapper.selectBatchIds(ids)) {
            map.put(doc.getId(), toDocumentDto(doc, false));
        }
        return success(map);
    }

    private KnowledgeDocumentRespDTO toDocumentDto(AiDocumentDO doc, boolean includeDomain) {
        KnowledgeDocumentRespDTO dto = new KnowledgeDocumentRespDTO();
        dto.setId(doc.getId());
        dto.setKbId(doc.getKbId());
        dto.setName(doc.getName());
        dto.setType(doc.getType());
        dto.setStoragePath(doc.getStoragePath());
        dto.setParseStatus(doc.getParseStatus());
        dto.setChunkStrategy(doc.getChunkStrategy());
        dto.setChunkStrategyParams(doc.getChunkStrategyParams());
        dto.setDomainMetadata(doc.getDomainMetadata());
        dto.setTenantId(doc.getTenantId());
        dto.setProducts(doc.getProducts());
        if (includeDomain) {
            AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(doc.getKbId());
            dto.setDomainCode(kb != null && kb.getDomainCode() != null ? kb.getDomainCode() : "GENERAL");
        }
        return dto;
    }

    @Override
    public CommonResult<List<Long>> getDocVersionIds(Long docId) {
        return success(aiDocVersionService.getVersionList(docId).stream().map(AiDocVersionDO::getId).toList());
    }

    @Override
    public CommonResult<Map<Long, KnowledgeVersionRespDTO>> getVersionMap(List<Long> versionIds) {
        Map<Long, KnowledgeVersionRespDTO> map = new HashMap<>();
        if (CollUtil.isEmpty(versionIds)) return success(map);
        for (AiDocVersionDO v : aiDocVersionService.getVersionListByIds(versionIds)) {
            KnowledgeVersionRespDTO dto = new KnowledgeVersionRespDTO();
            dto.setId(v.getId());
            dto.setDocId(v.getDocId());
            dto.setVersionNo(v.getVersionNo());
            dto.setStatus(v.getStatus());
            map.put(v.getId(), dto);
        }
        return success(map);
    }

    @Override
    public CommonResult<Set<Long>> getVisibleKbIds(Long userId) {
        if (userId == null) return success(java.util.Collections.emptySet());
        if (knowledgePermissionHelper.isSuperAdmin(userId)) {
            return success(aiKnowledgeBaseMapper.selectList().stream().map(AiKnowledgeBaseDO::getId).collect(Collectors.toSet()));
        }
        List<AiKnowledgeBaseDO> visible = knowledgePermissionHelper.filterVisibleKbs(userId, aiKnowledgeBaseMapper.selectList());
        return success(visible.stream().map(AiKnowledgeBaseDO::getId).collect(Collectors.toSet()));
    }

    @Override
    public CommonResult<Map<Long, String>> getDocumentVisibility(DocumentVisibilityReqDTO req) {
        Map<Long, String> result = new HashMap<>();
        if (req == null || CollUtil.isEmpty(req.getDocumentIds())) {
            return success(result);
        }
        List<Long> ids = req.getDocumentIds().stream().distinct().toList();
        Map<Long, AiDocumentDO> docs = aiDocumentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AiDocumentDO::getId, d -> d));
        Set<Long> visibleKbIds = getVisibleKbIds(req.getUserId()).getCheckedData();
        for (Long docId : ids) {
            AiDocumentDO doc = docs.get(docId);
            if (doc == null || visibleKbIds == null || !visibleKbIds.contains(doc.getKbId())) {
                // 文档不存在或所属知识库对当前用户不可见(文档级 ACL 继承 KB ACL)
                result.put(docId, "PERMISSION_CHANGED");
                continue;
            }
            if (!hasValidPublishedVersion(doc)) {
                result.put(docId, "STALE_RESULT_SET");
                continue;
            }
            result.put(docId, "VISIBLE");
        }
        return success(result);
    }

    /** 文档当前发布版本有效: 版本存在 + PUBLISHED + 处于生效区间 */
    private boolean hasValidPublishedVersion(AiDocumentDO doc) {
        Long versionId = doc.getVersionId();
        if (versionId == null) {
            return false;
        }
        AiDocVersionDO version = aiDocVersionMapper.selectById(versionId);
        if (version == null || !VersionStatusEnum.PUBLISHED.getStatus().equals(version.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (version.getEffectiveFrom() != null && version.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        if (version.getEffectiveTo() != null && version.getEffectiveTo().isBefore(now)) {
            return false;
        }
        return true;
    }

    @Override
    public CommonResult<List<IntentDTO>> getKbIntents(Long kbId) {
        List<AiIntentDO> intents = intentService.listEnabledByKb(kbId);
        return success(BeanUtils.toBean(intents, IntentDTO.class));
    }

    @Override
    public CommonResult<List<KnowledgeSlotDefinitionDTO>> getSlotDefinitions(List<Long> kbIds) {
        List<AiKnowledgeBaseSlotDO> slots = aiKnowledgeBaseSlotService.getEnabledByKbIds(kbIds);
        return success(BeanUtils.toBean(slots, KnowledgeSlotDefinitionDTO.class));
    }

    @Override
    public CommonResult<Map<Long, String>> getKbDomainCodes(List<Long> kbIds) {
        Map<Long, String> map = new HashMap<>();
        if (CollUtil.isEmpty(kbIds)) return success(map);
        for (AiKnowledgeBaseDO kb : aiKnowledgeBaseMapper.selectBatchIds(kbIds)) {
            map.put(kb.getId(), kb.getDomainCode() == null ? "GENERAL" : kb.getDomainCode());
        }
        return success(map);
    }

    @Override
    public CommonResult<List<Long>> lookupPatentDocuments(PatentDocumentLookupReqDTO req) {
        if (req == null || CollUtil.isEmpty(req.getKbIds())
                || (StrUtil.isBlank(req.getApplicationNo()) && StrUtil.isBlank(req.getPublicationNo()))) {
            return success(List.of());
        }
        String expectedApplicationNo = normalizeIdentifier(req.getApplicationNo());
        String expectedPublicationNo = normalizeIdentifier(req.getPublicationNo());
        List<Long> result = new ArrayList<>();
        for (AiDocumentDO doc : aiDocumentMapper.selectListByKbIds(req.getKbIds())) {
            if (StrUtil.isBlank(doc.getDomainMetadata())) continue;
            try {
                JSONObject meta = JSONUtil.parseObj(doc.getDomainMetadata());
                String applicationNo = normalizeIdentifier(meta.getStr("applicationNo"));
                String publicationNo = normalizeIdentifier(meta.getStr("publicationNo"));
                boolean applicationMatched = StrUtil.isBlank(expectedApplicationNo)
                        || expectedApplicationNo.equals(applicationNo);
                boolean publicationMatched = StrUtil.isBlank(expectedPublicationNo)
                        || expectedPublicationNo.equals(publicationNo);
                if (applicationMatched && publicationMatched) result.add(doc.getId());
            } catch (Exception ignore) {
                // 单个历史脏元数据不影响其它文档定位
            }
        }
        return success(result.stream().distinct().toList());
    }

    private String normalizeIdentifier(String value) {
        return StrUtil.isBlank(value) ? null : value.replaceAll("\\s+", "").toUpperCase();
    }

    @Override
    public CommonResult<Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>>> getKbScopes(List<Long> kbIds) {
        Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>> map = new HashMap<>();
        if (CollUtil.isEmpty(kbIds)) return success(map);
        for (cn.iocoder.yudao.module.knowledge.dal.dataobject.scope.AiKnowledgeScopeDO scope : aiKnowledgeScopeMapper.selectByKbIds(kbIds)) {
            cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO dto = new cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO();
            dto.setKbId(scope.getKbId());
            dto.setScopeType(scope.getScopeType());
            dto.setScopeCode(scope.getScopeCode());
            dto.setScopePriority(scope.getScopePriority());
            map.computeIfAbsent(scope.getKbId(), k -> new ArrayList<>()).add(dto);
        }
        return success(map);
    }

    @Override
    public CommonResult<Boolean> updateDocumentDomainMetadata(Map<String, Object> body) {
        Long documentId = body == null || body.get("documentId") == null ? null : ((Number) body.get("documentId")).longValue();
        String domainMetadata = body == null ? null : (String) body.get("domainMetadata");
        if (documentId == null || (domainMetadata != null && domainMetadata.length() > 65536)) return success(false);
        AiDocumentDO doc = aiDocumentMapper.selectById(documentId);
        if (doc == null) return success(false);
        AiDocumentDO update = new AiDocumentDO();
        update.setId(documentId);
        update.setDomainMetadata(domainMetadata);
        aiDocumentMapper.updateById(update);
        return success(true);
    }

    @Override
    public CommonResult<List<KnowledgePublishedChunkDTO>> getPublishedChunks(Long kbId) {
        return success(publishedContentCollector.collectPublishedChunks(kbId));
    }

    /**
     * 知识库聚合统计(AG-03): 按 metric 确定性计数, 禁止统计 chunk/evidence/vector hit/version 行。
     * <p>
     * metric:
     * <ul>
     *     <li>DOCUMENT_COUNT — 文档数(按 domainCode 可选过滤, 按 publishedOnly 过滤已发布)</li>
     *     <li>PATENT_COUNT — 去重专利数(按文档 domainMetadata.applicationNo, 默认 domainCode=PATENT)</li>
     *     <li>KNOWLEDGE_ENTRY_COUNT — 知识条目数(已发布文档的 chunkCount 合计)</li>
     * </ul>
     */
    @Override
    public CommonResult<Integer> aggregateCount(Long kbId, String metric, Boolean publishedOnly, String domainCode) {
        if (kbId == null || StrUtil.isBlank(metric)) {
            return success(0);
        }
        try {
            boolean published = !Boolean.FALSE.equals(publishedOnly);
            List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(kbId);
            if (CollUtil.isEmpty(docs)) {
                return success(0);
            }
            // 已发布过滤: 仅保留存在已发布版本的文档
            final Set<Long> publishedDocIds;
            if (published) {
                publishedDocIds = aiDocVersionMapper.selectPublishedByDocIds(
                        docs.stream().map(AiDocumentDO::getId).toList())
                        .stream().map(AiDocVersionDO::getDocId).collect(Collectors.toSet());
            } else {
                publishedDocIds = null;
            }
            List<AiDocumentDO> effective = docs.stream().filter(doc -> {
                if (publishedDocIds != null && !publishedDocIds.contains(doc.getId())) return false;
                if (StrUtil.isNotBlank(domainCode)) {
                    String docDomain = docDomainCode(doc);
                    if (!domainCode.equalsIgnoreCase(docDomain)) return false;
                }
                return true;
            }).toList();

            switch (metric.toUpperCase()) {
                case "DOCUMENT_COUNT":
                    return success(effective.size());
                case "PATENT_COUNT": {
                    Set<String> apps = new java.util.HashSet<>();
                    for (AiDocumentDO doc : effective) {
                        if ("PATENT".equalsIgnoreCase(domainCodeOf(doc, "PATENT"))) {
                            String app = docApplicationNo(doc);
                            if (StrUtil.isNotBlank(app)) apps.add(app);
                        }
                    }
                    return success(apps.size());
                }
                case "KNOWLEDGE_ENTRY_COUNT": {
                    int total = 0;
                    for (AiDocumentDO doc : effective) {
                        total += doc.getChunkCount() == null ? 0 : doc.getChunkCount();
                    }
                    return success(total);
                }
                default:
                    return success(0);
            }
        } catch (Exception e) {
            log.warn("[aggregateCount][kbId({}) metric({}) 统计失败, 返回 0: {}]", kbId, metric, e.getMessage());
            return success(0);
        }
    }

    /** 文档 domainMetadata 中的 domainCode */
    private String docDomainCode(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getStr("domainCode");
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 文档 domainMetadata 中的 applicationNo */
    private String docApplicationNo(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getStr("applicationNo");
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 文档 domainMetadata 中的 claimCount(未识别返回 null) */
    private Integer docClaimCount(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getInt("claimCount");
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Structured Query 数据访问(白名单化): 按 kbId + 已发布 + PATENT 领域(可选已解析文档集合)返回
     * 完整结构化数据集(每对象一行)。Core Executor 基于完整 rows 计算聚合, 禁止 TopK。
     */
    @Override
    public CommonResult<StructuredQueryRespDTO> structuredQuery(StructuredQueryReqDTO req) {
        StructuredQueryRespDTO resp = new StructuredQueryRespDTO();
        resp.setRows(new ArrayList<>());
        if (req == null || req.getKbId() == null || StrUtil.isBlank(req.getMetricCode())) {
            return success(resp);
        }
        try {
            boolean published = !Boolean.FALSE.equals(req.getPublishedOnly());
            List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(req.getKbId());
            if (CollUtil.isEmpty(docs)) {
                return success(resp);
            }
            // 已发布过滤
            final Set<Long> publishedDocIds;
            if (published) {
                publishedDocIds = aiDocVersionMapper.selectPublishedByDocIds(
                                docs.stream().map(AiDocumentDO::getId).toList())
                        .stream().map(AiDocVersionDO::getDocId).collect(Collectors.toSet());
            } else {
                publishedDocIds = null;
            }
            Set<Long> resolvedIds = req.getResolvedEntityIds() == null
                    ? Set.of() : new java.util.HashSet<>(req.getResolvedEntityIds());
            int cap = req.getRowCap() == null || req.getRowCap() <= 0 ? 2000 : req.getRowCap();

            for (AiDocumentDO doc : docs) {
                if (publishedDocIds != null && !publishedDocIds.contains(doc.getId())) continue;
                if (!"PATENT".equalsIgnoreCase(domainCodeOf(doc, "PATENT"))) continue;
                if (!resolvedIds.isEmpty() && !resolvedIds.contains(doc.getId())) continue;
                if (resp.getRows().size() >= cap) {
                    resp.setTruncated(true);
                    break;
                }
                StructuredQueryRowDTO row = new StructuredQueryRowDTO();
                row.setDocumentId(doc.getId());
                row.setDocumentName(doc.getName());
                row.setApplicationNo(docApplicationNo(doc));
                row.setPublicationNo(docPublicationNo(doc));
                row.setValue(metricValue(doc, req.getMetricCode()));
                resp.getRows().add(row);
            }
            return success(resp);
        } catch (Exception e) {
            log.warn("[structuredQuery][kbId({}) metric({}) 失败, 返回空: {}]",
                    req.getKbId(), req.getMetricCode(), e.getMessage());
            return success(resp);
        }
    }

    /** 单对象指标值(白名单 metric; DOCUMENT_COUNT 恒为 1, CLAIM_COUNT 取 claimCount) */
    private Double metricValue(AiDocumentDO doc, String metricCode) {
        switch (metricCode.toUpperCase()) {
            case "DOCUMENT_COUNT":
                return 1d;
            case "CLAIM_COUNT":
                Integer claimCount = docClaimCount(doc);
                return claimCount == null ? 0d : claimCount.doubleValue();
            default:
                return null;
        }
    }

    private String docPublicationNo(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getStr("publicationNo");
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 取文档 domainCode(显式传参优先, 否则读 metadata) */
    private String domainCodeOf(AiDocumentDO doc, String fallback) {
        String code = docDomainCode(doc);
        return StrUtil.isNotBlank(code) ? code : fallback;
    }

}
