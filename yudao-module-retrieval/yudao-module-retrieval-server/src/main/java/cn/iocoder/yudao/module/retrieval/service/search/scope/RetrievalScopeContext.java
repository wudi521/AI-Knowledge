package cn.iocoder.yudao.module.retrieval.service.search.scope;

import java.util.List;

/** Scope Pipeline 的强类型输入；documentIds 是当前已经确认的 hard scope。 */
public record RetrievalScopeContext(String query,
                                    Long tenantId,
                                    List<Long> kbIds,
                                    List<Long> documentIds,
                                    String domainCode) {
    public RetrievalScopeContext {
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public RetrievalScopeContext withDocumentIds(List<Long> next) {
        return new RetrievalScopeContext(query, tenantId, kbIds, next, domainCode);
    }
}
