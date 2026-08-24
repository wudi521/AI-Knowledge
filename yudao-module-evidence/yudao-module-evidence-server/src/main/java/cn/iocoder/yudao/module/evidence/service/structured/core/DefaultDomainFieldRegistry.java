package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认领域字段注册表(内存实现): domain → (fieldCode → FieldDefinition)。
 */
@Component
public class DefaultDomainFieldRegistry implements DomainFieldRegistry {

    private final Map<String, Map<String, FieldDefinition>> byDomain = new HashMap<>();

    @Override
    public void register(FieldDefinition field) {
        if (field == null || field.getDomainCode() == null || field.getFieldCode() == null) {
            return;
        }
        byDomain.computeIfAbsent(field.getDomainCode(), k -> new HashMap<>())
                .put(field.getFieldCode(), field);
    }

    @Override
    public Collection<FieldDefinition> all(String domainCode) {
        return byDomain.getOrDefault(domainCode, Map.of()).values();
    }

    @Override
    public Optional<FieldDefinition> byCode(String domainCode, String fieldCode) {
        Map<String, FieldDefinition> fields = byDomain.get(domainCode);
        return fields == null || fieldCode == null
                ? Optional.empty() : Optional.ofNullable(fields.get(fieldCode));
    }

    @Override
    public Optional<FieldDefinition> findByAlias(String query, String domainCode) {
        if (query == null || domainCode == null) {
            return Optional.empty();
        }
        FieldDefinition best = null;
        int bestLen = 0;
        for (FieldDefinition f : all(domainCode)) {
            if (f.getAliases() == null) continue;
            for (String alias : f.getAliases()) {
                if (alias != null && query.contains(alias) && alias.length() > bestLen) {
                    best = f;
                    bestLen = alias.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

}
