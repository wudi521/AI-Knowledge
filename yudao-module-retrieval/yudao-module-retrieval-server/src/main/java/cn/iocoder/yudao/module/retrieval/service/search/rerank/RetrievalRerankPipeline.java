package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每个领域确定性选择一个 Rerank 插件；精确领域优先，通用 * 兜底。 */
@Component
public class RetrievalRerankPipeline {

    private final DomainPluginResolver<RetrievalRerankPlugin> resolver;

    public RetrievalRerankPipeline(List<RetrievalRerankPlugin> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
        this.resolver.requireWildcardFallback("retrieval rerank");
    }

    public RetrievalRerankResult rerank(RetrievalRerankContext context) {
        DomainPluginContext pluginContext = new DomainPluginContext(null, null,
                context == null ? null : context.domainCode(), Set.of("RETRIEVAL_RERANK"), Map.of());
        RetrievalRerankPlugin plugin = resolver.requireFirst(pluginContext, "retrieval rerank");
        long start = System.currentTimeMillis();
        try {
            RetrievalRerankResult result = plugin.rerank(context);
            if (result == null) {
                return new RetrievalRerankResult(plugin.pluginId(), List.of(), true,
                        "plugin returned null result", System.currentTimeMillis() - start);
            }
            return new RetrievalRerankResult(plugin.pluginId(), result.rankings(), result.degraded(), result.message(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new RetrievalRerankResult(plugin.pluginId(), List.of(), true,
                    e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()),
                    System.currentTimeMillis() - start);
        }
    }
}
