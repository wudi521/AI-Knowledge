package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry for explicit Domain evidence -> entity mappings. */
@Component
public class DomainEvidenceEntityMapperRegistry {
    private final Map<String, DomainEvidenceEntityMapper> mappers;

    public DomainEvidenceEntityMapperRegistry(List<DomainEvidenceEntityMapper> mapperList) {
        Map<String, DomainEvidenceEntityMapper> mapped = new LinkedHashMap<>();
        if (mapperList != null) {
            for (DomainEvidenceEntityMapper mapper : mapperList) {
                if (mapper == null || StrUtil.isBlank(mapper.domainCode())) continue;
                String domain = normalize(mapper.domainCode());
                if (mapped.putIfAbsent(domain, mapper) != null) {
                    throw new IllegalStateException("duplicate DomainEvidenceEntityMapper for domain: " + domain);
                }
            }
        }
        this.mappers = Map.copyOf(mapped);
    }

    public List<Long> candidateEntityIds(String domainCode, List<Evidence> evidences) {
        if (StrUtil.isBlank(domainCode) || evidences == null || evidences.isEmpty()) return List.of();
        DomainEvidenceEntityMapper mapper = mappers.get(normalize(domainCode));
        if (mapper == null) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Evidence evidence : evidences) {
            if (evidence == null) continue;
            Long id = mapper.candidateEntityId(evidence);
            if (id != null && id > 0) ids.add(id);
        }
        return List.copyOf(ids);
    }

    public boolean hasMapper(String domainCode) {
        return StrUtil.isNotBlank(domainCode) && mappers.containsKey(normalize(domainCode));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
