package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.Collection;
import java.util.Optional;

/**
 * Domain Entity Registry SPI(Platform Core 通过 domainCode + entityCode 查实体定义)。
 */
public interface DomainEntityRegistry {

    /** 按 domainCode + entityCode 查实体定义 */
    Optional<EntityDefinition> lookup(String domainCode, String entityCode);

    /** 在指定领域内按同义词查实体(如 "专利" → PATENT_DOCUMENT) */
    Optional<EntityDefinition> findByAlias(String domainCode, String alias);

    /** 注册实体定义 */
    void register(EntityDefinition definition);

    /** 该领域已注册的全部实体 */
    Collection<EntityDefinition> all(String domainCode);

}
