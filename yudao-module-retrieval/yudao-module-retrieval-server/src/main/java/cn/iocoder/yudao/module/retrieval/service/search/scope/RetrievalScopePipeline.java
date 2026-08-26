package cn.iocoder.yudao.module.retrieval.service.search.scope;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顺序执行当前领域的 Scope 插件，范围只能越来越窄。
 * 没有插件时保持原 scope；任何插件 blocked 后立即停止，禁止回退全库。
 */
@Component
public class RetrievalScopePipeline {

    private final DomainPluginResolver<RetrievalScopePlugin> resolver;

    public RetrievalScopePipeline(List<RetrievalScopePlugin> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
    }

    public Result refine(RetrievalScopeContext initial) {
        RetrievalScopeContext current = initial;
        List<RetrievalScopeDecision> decisions = new ArrayList<>();
        DomainPluginContext pluginContext = new DomainPluginContext(initial.tenantId(),
                initial.kbIds().isEmpty() ? null : initial.kbIds().get(0), initial.domainCode(),
                Set.of("RETRIEVAL_SCOPE"), Map.of());
        for (RetrievalScopePlugin plugin : resolver.resolve(pluginContext)) {
            RetrievalScopeDecision decision;
            try {
                decision = plugin.refine(current);
            } catch (Exception e) {
                decision = new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), true, true, true,
                        e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            }
            if (decision == null) {
                decision = new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), true, true, true,
                        "plugin returned null decision");
            }
            decisions.add(decision);
            if (decision.applied()) current = current.withDocumentIds(decision.documentIds());
            if (decision.blocked()) {
                return new Result(current.documentIds(), true,
                        decisions.stream().anyMatch(RetrievalScopeDecision::degraded), List.copyOf(decisions));
            }
        }
        return new Result(current.documentIds(), false,
                decisions.stream().anyMatch(RetrievalScopeDecision::degraded), List.copyOf(decisions));
    }

    public record Result(List<Long> documentIds,
                         boolean blocked,
                         boolean degraded,
                         List<RetrievalScopeDecision> decisions) {
        public Result {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }
}
