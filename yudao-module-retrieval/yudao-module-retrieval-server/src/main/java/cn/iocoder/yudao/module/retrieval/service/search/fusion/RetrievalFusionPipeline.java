package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每个领域确定性选择一个 Fusion 插件；精确领域优先，通用 * 兜底。 */
@Component
public class RetrievalFusionPipeline {

    private final DomainPluginResolver<RetrievalFusionPlugin> resolver;

    public RetrievalFusionPipeline(List<RetrievalFusionPlugin> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
        this.resolver.requireWildcardFallback("retrieval fusion");
    }

    public RetrievalFusionResult fuse(RetrievalFusionContext context) {
        DomainPluginContext pluginContext = new DomainPluginContext(null, null,
                context == null ? null : context.domainCode(), Set.of("RETRIEVAL_FUSION"), Map.of());
        RetrievalFusionPlugin plugin = resolver.requireFirst(pluginContext, "retrieval fusion");
        long start = System.currentTimeMillis();
        try {
            RetrievalFusionResult result = plugin.fuse(context);
            if (result == null) {
                return new RetrievalFusionResult(plugin.pluginId(), List.of(), true,
                        "plugin returned null result", System.currentTimeMillis() - start);
            }
            return new RetrievalFusionResult(plugin.pluginId(), result.hits(), result.degraded(), result.message(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new RetrievalFusionResult(plugin.pluginId(), List.of(), true,
                    e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()),
                    System.currentTimeMillis() - start);
        }
    }
}
