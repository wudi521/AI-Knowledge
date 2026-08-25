package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.agent.capability.DomainEvidenceEntityMapper;
import org.springframework.stereotype.Component;

/**
 * Patent Domain 的检索证据 -> 结构化实体映射。
 * PatentStructuredDataAdapter 明确使用 KnowledgeDocument documentId 作为 entityId，
 * 因此本领域可以安全把检索 Evidence.documentId 暴露为 candidateEntityId。
 */
@Component
public class PatentEvidenceEntityMapper implements DomainEvidenceEntityMapper {

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public Long candidateEntityId(Evidence evidence) {
        if (evidence == null || StrUtil.isBlank(evidence.getDocumentId())) return null;
        try {
            long id = Long.parseLong(evidence.getDocumentId().trim());
            return id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }
}
