package cn.iocoder.yudao.module.retrieval.service.search.scope;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顺序执行当前领域的 Scope 插件，范围只能越来越窄。
 * 没有插件时保持原 scope；任何插件 blocked 后立即停止，禁止回退全库。
 *
 * <p>“只能收窄”是 Pipeline 强制的不变量，不信任插件自行遵守：
 * 已有 hard scope 非空时，新 scope 必须是其子集；applied=true 且结果为空自动视为 blocked，
 * 防止空集合被后续 Recall 误解成“不限 documentIds”而意外扩大到全库。</p>
 */
@Component
public class RetrievalScopePipeline {

    private final DomainPluginResolver<RetrievalScopePlugin> resolver;

    public RetrievalScopePipeline(List<RetrievalScopePlugin> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
    }

    public Result refine(RetrievalScopeContext initial) {
        if (initial == null) throw new IllegalArgumentException("retrieval scope context must not be null");
        RetrievalScopeContext current = initial;
        List<RetrievalScopeDecision> decisions = new ArrayList<>();
        DomainPluginContext pluginContext = new DomainPluginContext(initial.tenantId(),
                initial.kbIds().isEmpty() ? null : initial.kbIds().get(0), initial.domainCode(),
                Set.of("RETRIEVAL_SCOPE"), Map.of());
        for (RetrievalScopePlugin plugin : resolver.resolve(pluginContext)) {
            RetrievalScopeDecision decision;
            try {
                decision = normalize(plugin, current, plugin.refine(current));
            } catch (Exception e) {
                decision = new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), true, true, true,
                        e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
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

    private RetrievalScopeDecision normalize(RetrievalScopePlugin plugin,
                                             RetrievalScopeContext current,
                                             RetrievalScopeDecision raw) {
        if (raw == null) {
            return new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), true, true, true,
                    "plugin returned null decision");
        }
        if (!raw.applied()) {
            return new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), false,
                    raw.blocked(), raw.degraded(), raw.message());
        }

        List<Long> next = raw.documentIds() == null ? List.of()
                : raw.documentIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!current.documentIds().isEmpty()) {
            Set<Long> allowed = new LinkedHashSet<>(current.documentIds());
            boolean broadened = next.stream().anyMatch(id -> !allowed.contains(id));
            if (broadened) {
                return new RetrievalScopeDecision(plugin.pluginId(), current.documentIds(), true, true, true,
                        "scope plugin attempted to broaden existing hard scope");
            }
        }
        boolean blocked = raw.blocked() || next.isEmpty();
        String message = next.isEmpty() && !raw.blocked()
                ? "applied scope resolved to empty set" : raw.message();
        return new RetrievalScopeDecision(plugin.pluginId(), next, true, blocked, raw.degraded(), message);
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
