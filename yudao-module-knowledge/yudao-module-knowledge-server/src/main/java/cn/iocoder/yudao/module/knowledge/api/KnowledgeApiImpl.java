package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

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

}
