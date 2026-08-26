package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import java.util.List;

/** Rerank Pipeline 的强类型输入。 */
public record RetrievalRerankContext(String query,
                                     List<String> candidateContents,
                                     String domainCode) {
    public RetrievalRerankContext {
        candidateContents = candidateContents == null ? List.of() : List.copyOf(candidateContents);
    }
}
