package cn.iocoder.yudao.module.retrieval.service.search.scope;

import java.util.List;

/** 单个 Scope 插件的范围收敛结果。blocked=true 表示确定性 hard scope 已证明无候选，禁止回退全库检索。 */
public record RetrievalScopeDecision(String pluginId,
                                     List<Long> documentIds,
                                     boolean applied,
                                     boolean blocked,
                                     boolean degraded,
                                     String message) {
    public RetrievalScopeDecision {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public static RetrievalScopeDecision unchanged(String pluginId, List<Long> documentIds) {
        return new RetrievalScopeDecision(pluginId, documentIds, false, false, false, null);
    }
}
