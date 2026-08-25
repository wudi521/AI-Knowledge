package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

/**
 * Domain 层显式声明“检索 Evidence 如何对应业务 entityId”。
 *
 * <p>Document 是知识载体，不保证等于业务实体。公共 Runtime 禁止从 documentId 猜 entityId；
 * 只有注册了本 SPI 的领域，检索证据才会形成 candidateEntityIds。</p>
 */
public interface DomainEvidenceEntityMapper {

    String domainCode();

    /** 无法从该 Evidence 安全映射到业务实体时返回 null。 */
    Long candidateEntityId(Evidence evidence);
}
