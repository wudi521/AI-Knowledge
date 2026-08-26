package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 Recall Pipeline：发现当前领域可用的召回插件并按统一顺序执行。
 *
 * <p>它不理解专利/合同/客服语义，也不决定用户 intent；领域差异只通过插件 supportedDomains/supports 表达。</p>
 */
@Component
public class RetrievalRecallPipeline {

    private final DomainPluginResolver<RetrievalRecallPlugin> resolver;

    public RetrievalRecallPipeline(List<RetrievalRecallPlugin> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
        this.resolver.requireWildcardFallback("retrieval recall");
    }

    public List<RetrievalRecallResult> recall(RetrievalRecallContext context) {
        DomainPluginContext pluginContext = new DomainPluginContext(
                context == null ? null : context.tenantId(),
                context == null || context.kbIds().isEmpty() ? null : context.kbIds().get(0),
                context == null ? null : context.domainCode(),
                Set.of("RETRIEVAL_RECALL"), attributes(context));
        List<RetrievalRecallPlugin> plugins = resolver.resolve(pluginContext);
        List<RetrievalRecallResult> results = new ArrayList<>(plugins.size());
        for (RetrievalRecallPlugin plugin : plugins) {
            long start = System.currentTimeMillis();
            try {
                RetrievalRecallResult raw = plugin.recall(context);
                if (raw == null) {
                    results.add(new RetrievalRecallResult(plugin.pluginId(), plugin.channel(), List.of(), true,
                            "plugin returned null result", System.currentTimeMillis() - start));
                } else {
                    results.add(new RetrievalRecallResult(plugin.pluginId(), plugin.channel(), raw.hits(),
                            raw.degraded(), raw.message(), System.currentTimeMillis() - start));
                }
            } catch (Exception e) {
                results.add(new RetrievalRecallResult(plugin.pluginId(), plugin.channel(), List.of(), true,
                        e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage()),
                        System.currentTimeMillis() - start));
            }
        }
        return List.copyOf(results);
    }

    public List<RetrievalRecallPlugin> pluginsFor(String domainCode) {
        return resolver.resolve(DomainPluginContext.of(domainCode));
    }

    private Map<String, Object> attributes(RetrievalRecallContext context) {
        if (context == null) return Map.of();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("scopedDocuments", !context.documentIds().isEmpty());
        attributes.put("variantCount", context.variants().size());
        attributes.put("topK", context.topK());
        return Map.copyOf(attributes);
    }

    private String safeMessage(String message) {
        if (message == null) return "";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
