package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.Collection;
import java.util.Optional;

/**
 * 领域字段注册表(Domain Field Registry, CQ-11)
 * <p>
 * Domain Pack 注册 fieldCode/aliases; Core 通过别名匹配字段, 不感知具体业务词。
 */
public interface DomainFieldRegistry {

    void register(FieldDefinition field);

    /** 该领域全部字段 */
    Collection<FieldDefinition> all(String domainCode);

    /** 按字段编码精确查找 */
    Optional<FieldDefinition> byCode(String domainCode, String fieldCode);

    /** 按中文别名(最长匹配优先)查找字段 */
    Optional<FieldDefinition> findByAlias(String query, String domainCode);

}
