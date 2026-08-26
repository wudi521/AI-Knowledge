package cn.iocoder.yudao.framework.common.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 领域插件的通用发现器。
 *
 * <p>选择规则固定为：支持当前上下文 -> 精确领域优先于 * -> order -> pluginId。
 * 这套规则由框架统一维护，切片/检索/验证 Pipeline 不再各自实现一份 Registry。</p>
 */
public final class DomainPluginResolver<T extends DomainPipelinePlugin> {

    private final List<T> plugins;

    public DomainPluginResolver(Collection<T> plugins) {
        List<T> safe = plugins == null ? List.of() : plugins.stream().filter(java.util.Objects::nonNull).toList();
        Set<String> ids = new HashSet<>();
        for (T plugin : safe) {
            if (plugin.pluginId() == null || plugin.pluginId().isBlank()) {
                throw new IllegalStateException("domain pluginId must not be blank: " + plugin.getClass().getName());
            }
            if (!ids.add(plugin.pluginId())) {
                throw new IllegalStateException("duplicate domain pluginId: " + plugin.pluginId());
            }
        }
        this.plugins = List.copyOf(safe);
    }

    public List<T> resolve(DomainPluginContext context) {
        DomainPluginContext safeContext = context == null ? DomainPluginContext.of("GENERAL") : context;
        List<T> matched = new ArrayList<>();
        for (T plugin : plugins) if (plugin.supports(safeContext)) matched.add(plugin);
        matched.sort(Comparator
                .comparingInt((T plugin) -> specificity(plugin, safeContext))
                .thenComparingInt(DomainPipelinePlugin::order)
                .thenComparing(DomainPipelinePlugin::pluginId));
        return List.copyOf(matched);
    }

    public T requireFirst(DomainPluginContext context, String pipelineName) {
        List<T> matched = resolve(context);
        if (matched.isEmpty()) {
            String domain = context == null ? "GENERAL" : context.domainCode();
            throw new IllegalStateException("no " + pipelineName + " plugin supports domain " + domain);
        }
        return matched.get(0);
    }

    /**
     * 对必须对未知/新领域可工作的 Pipeline，启动时校验至少存在一个 `*` 通用插件。
     * Scope/Validation 这类“允许零插件”的阶段不要调用本方法。
     */
    public void requireWildcardFallback(String pipelineName) {
        boolean found = plugins.stream().anyMatch(this::supportsWildcard);
        if (!found) {
            throw new IllegalStateException("no wildcard (*) fallback configured for " + pipelineName + " pipeline");
        }
    }

    public boolean hasWildcardFallback() {
        return plugins.stream().anyMatch(this::supportsWildcard);
    }

    public List<T> all() {
        return plugins;
    }

    private boolean supportsWildcard(T plugin) {
        Set<String> domains = plugin == null ? null : plugin.supportedDomains();
        return domains != null && domains.stream().anyMatch("*"::equals);
    }

    private int specificity(T plugin, DomainPluginContext context) {
        Set<String> domains = plugin.supportedDomains();
        if (domains != null) {
            for (String candidate : domains) {
                if (candidate != null && !"*".equals(candidate)
                        && DomainPluginContext.normalizeDomain(candidate).equals(context.domainCode())) {
                    return 0;
                }
            }
        }
        return 1;
    }
}
