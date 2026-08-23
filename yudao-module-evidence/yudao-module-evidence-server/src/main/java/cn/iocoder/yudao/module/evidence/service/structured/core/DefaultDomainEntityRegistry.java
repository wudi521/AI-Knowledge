package cn.iocoder.yudao.module.evidence.service.structured.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Domain Entity Registry 实现(Platform Core)。
 */
@Slf4j
@Component
public class DefaultDomainEntityRegistry implements DomainEntityRegistry {

    private final Map<String, EntityDefinition> entities = new ConcurrentHashMap<>();
    private final Map<String, String> aliasIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<EntityDefinition> lookup(String domainCode, String entityCode) {
        if (domainCode == null || entityCode == null) return Optional.empty();
        return Optional.ofNullable(entities.get(key(domainCode, entityCode)));
    }

    @Override
    public Optional<EntityDefinition> findByAlias(String domainCode, String alias) {
        if (domainCode == null || alias == null) return Optional.empty();
        String entityCode = aliasIndex.get(key(domainCode, alias));
        return entityCode == null ? Optional.empty() : lookup(domainCode, entityCode);
    }

    @Override
    public void register(EntityDefinition definition) {
        if (definition == null || definition.getDomainCode() == null || definition.getEntityCode() == null) return;
        entities.put(key(definition.getDomainCode(), definition.getEntityCode()), definition);
        if (definition.getAliases() != null) {
            for (String alias : definition.getAliases()) {
                aliasIndex.put(key(definition.getDomainCode(), alias.trim()), definition.getEntityCode());
            }
        }
        log.info("[register][domain({}) entity({}) 已注册, aliases({})]",
                definition.getDomainCode(), definition.getEntityCode(), definition.getAliases());
    }

    @Override
    public Collection<EntityDefinition> all(String domainCode) {
        if (domainCode == null) return List.of();
        List<EntityDefinition> result = new ArrayList<>();
        entities.forEach((k, v) -> {
            if (k.startsWith(domainCode + ":")) result.add(v);
        });
        return result;
    }

    private String key(String domainCode, String code) {
        return domainCode + ":" + code;
    }
}
