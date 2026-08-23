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
 * 默认 Domain Metric Registry 实现(Platform Core)。
 * <p>
 * key = domainCode:metricCode; 另建 alias 倒排索引(同义词 → 指标)。
 */
@Slf4j
@Component
public class DefaultDomainMetricRegistry implements DomainMetricRegistry {

    private final Map<String, MetricDefinition> metrics = new ConcurrentHashMap<>();
    private final Map<String, String> aliasIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<MetricDefinition> lookup(String domainCode, String metricCode) {
        if (domainCode == null || metricCode == null) return Optional.empty();
        return Optional.ofNullable(metrics.get(key(domainCode, metricCode)));
    }

    @Override
    public Optional<MetricDefinition> findByAlias(String domainCode, String alias) {
        if (domainCode == null || alias == null) return Optional.empty();
        String metricCode = aliasIndex.get(key(domainCode, alias));
        return metricCode == null ? Optional.empty() : lookup(domainCode, metricCode);
    }

    @Override
    public void register(MetricDefinition definition) {
        if (definition == null || definition.getDomainCode() == null || definition.getMetricCode() == null) return;
        metrics.put(key(definition.getDomainCode(), definition.getMetricCode()), definition);
        if (definition.getAliases() != null) {
            for (String alias : definition.getAliases()) {
                aliasIndex.put(key(definition.getDomainCode(), alias.trim()), definition.getMetricCode());
            }
        }
        log.info("[register][domain({}) metric({}) 已注册, aliases({})]",
                definition.getDomainCode(), definition.getMetricCode(), definition.getAliases());
    }

    @Override
    public Collection<MetricDefinition> all(String domainCode) {
        if (domainCode == null) return List.of();
        List<MetricDefinition> result = new ArrayList<>();
        metrics.forEach((k, v) -> {
            if (k.startsWith(domainCode + ":")) result.add(v);
        });
        return result;
    }

    private String key(String domainCode, String code) {
        return domainCode + ":" + code;
    }
}
