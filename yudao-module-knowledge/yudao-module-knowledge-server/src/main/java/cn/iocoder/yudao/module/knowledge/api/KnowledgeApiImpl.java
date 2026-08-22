package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.api.dto.IntentDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeSlotDefinitionDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgePublishedChunkDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.common.PublishedContentCollector;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiKnowledgeBaseSlotService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.KnowledgePermissionHelper;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import cn.hutool.core.collection.CollUtil;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 知识平台 对外 RPC 实现
 */
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class KnowledgeApiImpl implements KnowledgeApi {

    @Resource
    private AiDocumentService aiDocumentService;

    @Resource
    private AiDocVersionService aiDocVersionService;
    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;
    @Resource
    private cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper aiDocumentMapper;
    @Resource
    private KnowledgePermissionHelper knowledgePermissionHelper;
    @Resource
    private IntentService intentService;
    @Resource
    private AiKnowledgeBaseSlotService aiKnowledgeBaseSlotService;
    @Resource
    private PublishedContentCollector publishedContentCollector;
    @Resource
    private cn.iocoder.yudao.module.knowledge.dal.mysql.scope.AiKnowledgeScopeMapper aiKnowledgeScopeMapper;

    @Override
    public Boolean checkKnowledgePermission(Long chunkId, Long userId) {
        return true;
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
        if (doc == null) {
            return success(null);
        }
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
        // 领域代码: 从知识库带(文档自身无领域字段; 知识库可能已删则 GENERAL)
        AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(doc.getKbId());
        dto.setDomainCode(kb != null && kb.getDomainCode() != null ? kb.getDomainCode() : "GENERAL");
        dto.setTenantId(doc.getTenantId());
        // 当前版本编号(管线写 chunk.version_id 用)
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
        if (CollUtil.isEmpty(ids)) {
            return success(map);
        }
        for (AiDocumentDO doc : aiDocumentMapper.selectBatchIds(ids)) {
            KnowledgeDocumentRespDTO dto = new KnowledgeDocumentRespDTO();
            dto.setId(doc.getId());
            dto.setKbId(doc.getKbId());
            dto.setName(doc.getName());
            dto.setType(doc.getType());
            dto.setStoragePath(doc.getStoragePath());
            dto.setParseStatus(doc.getParseStatus());
            dto.setTenantId(doc.getTenantId());
            dto.setProducts(doc.getProducts());
            map.put(doc.getId(), dto);
        }
        return success(map);
    }

    @Override
    public CommonResult<List<Long>> getDocVersionIds(Long docId) {
        return success(aiDocVersionService.getVersionList(docId).stream()
                .map(AiDocVersionDO::getId).toList());
    }

    @Override
    public CommonResult<Map<Long, KnowledgeVersionRespDTO>> getVersionMap(List<Long> versionIds) {
        Map<Long, KnowledgeVersionRespDTO> map = new HashMap<>();
        if (CollUtil.isEmpty(versionIds)) {
            return success(map);
        }
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
        // fail-closed(越权 0 容忍): userId 为空(未登录/未传)时拒绝返回全量可见,
        // 返回空集, 由调用方(检索/证据)按现有短路逻辑降级为空结果, 不泄露任何知识库。
        if (userId == null) {
            return success(java.util.Collections.emptySet());
        }
        if (knowledgePermissionHelper.isSuperAdmin(userId)) {
            return success(aiKnowledgeBaseMapper.selectList().stream()
                    .map(AiKnowledgeBaseDO::getId).collect(Collectors.toSet()));
        }
        List<AiKnowledgeBaseDO> visible = knowledgePermissionHelper.filterVisibleKbs(userId, aiKnowledgeBaseMapper.selectList());
        return success(visible.stream().map(AiKnowledgeBaseDO::getId).collect(Collectors.toSet()));
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
    public CommonResult<Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>>> getKbScopes(List<Long> kbIds) {
        Map<Long, List<cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeScopeDTO>> map = new HashMap<>();
        if (CollUtil.isEmpty(kbIds)) {
            return success(map);
        }
        for (cn.iocoder.yudao.module.knowledge.dal.dataobject.scope.AiKnowledgeScopeDO scope
                : aiKnowledgeScopeMapper.selectByKbIds(kbIds)) {
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
    public CommonResult<List<KnowledgePublishedChunkDTO>> getPublishedChunks(Long kbId) {
        return success(publishedContentCollector.collectPublishedChunks(kbId));
    }

}
