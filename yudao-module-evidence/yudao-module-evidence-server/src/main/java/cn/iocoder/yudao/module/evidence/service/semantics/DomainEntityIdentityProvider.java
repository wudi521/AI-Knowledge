package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

/**
 * Domain Entity Identity SPI。
 * <p>
 * Document 是知识载体，不等于业务实体。Core 在去重/比较时只依赖该 SPI；
 * Patent/Telecom/Manufacturing 等 Domain Pack 自己定义业务实体身份。
 */
public interface DomainEntityIdentityProvider {

    /** 领域编码，例如 PATENT / TELECOM。 */
    String domainCode();

    /**
     * 返回稳定业务身份键；无法识别时返回 null，Core 回退到 documentId。
     */
    String identityKey(Evidence evidence, Long documentId);
}
