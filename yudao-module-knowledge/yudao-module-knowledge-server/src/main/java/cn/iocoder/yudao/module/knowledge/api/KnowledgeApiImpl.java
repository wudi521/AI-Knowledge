package cn.iocoder.yudao.module.knowledge.api;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
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
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.enums.version.VersionStatusEnum;
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
    @Resource private IngestionApi ingestionApi;

    /**
     * chunk 级权限校验：chunk -> document -> KB ACL。任何缺失/RPC异常一律 fail-closed。
     * 旧实现 return true 会让调用方绕过知识库 ACL，商用环境不可接受。
     */
    @Override
    public Boolean checkKnowledgePermission(Long chunkId, Long userId) {
        if (chunkId == null || userId == null) return false;
        try {
            Map<Long, ChunkDocInfoDTO> infoMap = ingestionApi.getChunkDocInfo(List.of(chunkId)).getCheckedData();
            ChunkDocInfoDTO info = infoMap == null ? null : infoMap.get(chunkId);
            if (info == null || info.getDocumentId() == null) return false;
            AiDocumentDO doc = aiDocumentMapper.selectById(info.getDocumentId());
            if (doc == null || doc.getKbId() == null) return false;
            AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(doc.getKbId());
            return kb != null && knowledgePermissionHelper.isKbVisibleToUser(userId, kb);
        } catch (Exception e) {
            log.warn("[checkKnowledgePermission][chunkId({}) userId({}) 校验异常, fail-closed: {}]",
                    chunkId, userId, e.getMessage());
            return false;
        }
    }

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
        if (req == null || CollUtil.isEmpty(req.getDocumentIds()) || req.getUserId() == null) {
            return success(result);
        }
        List<Long> ids = req.getDocumentIds().stream().distinct().toList();
        Map<Long, AiDocumentDO> docs = aiDocumentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AiDocumentDO::getId, d -> d));
        Set<Long> visibleKbIds = getVisibleKbIds(req.getUserId()).getCheckedData();
        for (Long docId : ids) {
            AiDocumentDO doc = docs.get(docId);
            if (doc == null || visibleKbIds == null || !visibleKbIds.contains(doc.getKbId())) {
                result.put(docId, "PERMISSION_CHANGED");
                continue;
            }
            if (!hasValidPublishedVersion(doc.getId())) {
                result.put(docId, "STALE_RESULT_SET");
                continue;
            }
            result.put(docId, "VISIBLE");
        }
        return success(result);
    }

    /** 不读取 AiDocumentDO.versionId 非表字段；直接查询该文档真实 PUBLISHED version。 */
    private boolean hasValidPublishedVersion(Long documentId) {
        if (documentId == null) return false;
        List<AiDocVersionDO> published = aiDocVersionMapper.selectPublishedByDocIds(List.of(documentId));
        if (CollUtil.isEmpty(published)) return false;
        LocalDateTime now = LocalDateTime.now();
        return published.stream().anyMatch(version ->
                VersionStatusEnum.PUBLISHED.getStatus().equals(version.getStatus())
                        && (version.getEffectiveFrom() == null || !version.getEffectiveFrom().isAfter(now))
                        && (version.getEffectiveTo() == null || !version.getEffectiveTo().isBefore(now)));
    }

    @Override
    public CommonResult<List<Long>> getPublishedDocumentIds(Long kbId) {
        if (kbId == null) return success(List.of());
        List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(kbId);
        if (docs.isEmpty()) return success(List.of());
        List<Long> docIds = docs.stream().map(AiDocumentDO::getId).toList();
        List<AiDocVersionDO> published = aiDocVersionMapper.selectPublishedByDocIds(docIds);
        return success(published.stream().map(AiDocVersionDO::getDocId).distinct().toList());
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
        try {
            List<Long> indexed = aiDocumentMapper.selectPatentDocumentIdsByIdentifier(
                    req.getKbIds(), expectedApplicationNo, expectedPublicationNo);
            return success(validPublishedDocumentIds(indexed));
        } catch (Exception e) {
            if (!isIdentifierIndexMissing(e)) {
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("专利标识符索引查询失败", e);
            }
            // 只兼容应用先发布、DDL 后执行的滚动升级；数据库故障禁止回退全扫描，避免故障放大。
            log.warn("[lookupPatentDocuments][专利标识符索引列尚未迁移，兼容回退旧扫描: {}]", e.getMessage());
        }
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
        return success(validPublishedDocumentIds(result));
    }

    private List<Long> validPublishedDocumentIds(List<Long> candidateIds) {
        if (CollUtil.isEmpty(candidateIds)) return List.of();
        LocalDateTime now = LocalDateTime.now();
        Set<Long> candidates = Set.copyOf(candidateIds);
        return aiDocVersionMapper.selectPublishedByDocIds(candidates).stream()
                .filter(version -> version != null && candidates.contains(version.getDocId()))
                .filter(version -> version.getEffectiveFrom() == null || !version.getEffectiveFrom().isAfter(now))
                .filter(version -> version.getEffectiveTo() == null || !version.getEffectiveTo().isBefore(now))
                .map(AiDocVersionDO::getDocId).distinct().toList();
    }

    private boolean isIdentifierIndexMissing(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if ((lower.contains("unknown column") || lower.contains("doesn't exist")
                        || lower.contains("does not exist"))
                        && (lower.contains("patent_application_no_norm")
                        || lower.contains("patent_publication_no_norm"))) return true;
            }
            current = current.getCause();
        }
        return false;
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

    @Override
    public CommonResult<Integer> aggregateCount(Long kbId, String metric, Boolean publishedOnly, String domainCode) {
        if (kbId == null || StrUtil.isBlank(metric)) return success(0);
        try {
            boolean published = !Boolean.FALSE.equals(publishedOnly);
            List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(kbId);
            if (CollUtil.isEmpty(docs)) return success(0);
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
                    for (AiDocumentDO doc : effective) total += doc.getChunkCount() == null ? 0 : doc.getChunkCount();
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

    private String docDomainCode(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getStr("domainCode");
        } catch (Exception ignore) {
            return null;
        }
    }

    private String docApplicationNo(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getStr("applicationNo");
        } catch (Exception ignore) {
            return null;
        }
    }

    private Integer docClaimCount(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            return JSONUtil.parseObj(doc.getDomainMetadata()).getInt("claimCount");
        } catch (Exception ignore) {
            return null;
        }
    }

    @Override
    public CommonResult<StructuredQueryRespDTO> structuredQuery(StructuredQueryReqDTO req) {
        StructuredQueryRespDTO resp = new StructuredQueryRespDTO();
        resp.setRows(new ArrayList<>());
        if (req == null || req.getKbId() == null || StrUtil.isBlank(req.getMetricCode())) return success(resp);
        try {
            boolean published = !Boolean.FALSE.equals(req.getPublishedOnly());
            List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(req.getKbId());
            if (CollUtil.isEmpty(docs)) return success(resp);
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
                JSONObject metadata = docMetadata(doc);
                row.setTitle(metadataValue(metadata, "title"));
                row.setApplicationNo(metadataValue(metadata, "applicationNo"));
                row.setPublicationNo(metadataValue(metadata, "publicationNo"));
                row.setApplicant(metadataListValue(metadata, "applicants", "applicant"));
                row.setInventor(metadataListValue(metadata, "inventors", "inventor"));
                row.setFilingDate(metadataValue(metadata, "filingDate", "applicationDate"));
                row.setPublicationDate(metadataValue(metadata, "publicationDate", "publishDate"));
                row.setValue(metricValue(doc, req.getMetricCode()));
                resp.getRows().add(row);
            }
            return success(resp);
        } catch (Exception e) {
            // 数据源故障与“完整数据集确实为空”是两种不同事实。这里必须失败关闭，
            // 由上层转成 UNSUPPORTED，禁止把数据库/RPC 异常包装成可信空集。
            log.error("[structuredQuery][kbId({}) metric({}) 结构化数据读取失败]",
                    req.getKbId(), req.getMetricCode(), e);
            throw new IllegalStateException("结构化数据读取失败", e);
        }
    }

    private JSONObject docMetadata(AiDocumentDO doc) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return new JSONObject();
        return JSONUtil.parseObj(doc.getDomainMetadata());
    }

    private String metadataValue(JSONObject metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            String value = metadata.getStr(key);
            if (StrUtil.isNotBlank(value)) return value;
        }
        return null;
    }

    private String metadataListValue(JSONObject metadata, String pluralKey, String singularKey) {
        if (metadata == null) return null;
        Object raw = metadata.get(pluralKey);
        if (raw instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            for (Object value : values) {
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) items.add(String.valueOf(value));
            }
            if (!items.isEmpty()) return String.join("、", items);
        }
        return metadataValue(metadata, singularKey);
    }

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

    private String domainCodeOf(AiDocumentDO doc, String fallback) {
        String code = docDomainCode(doc);
        return StrUtil.isNotBlank(code) ? code : fallback;
    }

}
