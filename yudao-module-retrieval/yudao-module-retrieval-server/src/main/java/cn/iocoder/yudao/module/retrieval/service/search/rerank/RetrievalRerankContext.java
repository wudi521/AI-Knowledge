package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import java.util.List;

/**
 * Rerank Pipeline 的强类型输入。
 *
 * @param scopedDocumentIds Scope 阶段已经权威收敛出的文档范围；非空表示后续插件不得再用 chunk 文本重新猜该文档约束。
 */
public record RetrievalRerankContext(String query,
                                     List<String> candidateContents,
                                     String domainCode,
                                     List<Long> scopedDocumentIds) {
    public RetrievalRerankContext {
        candidateContents = candidateContents == null ? List.of() : List.copyOf(candidateContents);
        scopedDocumentIds = scopedDocumentIds == null ? List.of() : List.copyOf(scopedDocumentIds);
    }

    /** 迁移兼容构造；直接单测插件时可省略 Scope provenance。 */
    public RetrievalRerankContext(String query, List<String> candidateContents, String domainCode) {
        this(query, candidateContents, domainCode, List.of());
    }

    public boolean hardScoped() {
        return !scopedDocumentIds.isEmpty();
    }
}
