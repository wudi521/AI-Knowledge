package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import cn.iocoder.yudao.module.retrieval.service.search.recall.RetrievalRecallResult;

import java.util.List;

/** Fusion Pipeline 的强类型输入。 */
public record RetrievalFusionContext(String domainCode,
                                     List<RetrievalRecallResult> recalls,
                                     int topK) {
    public RetrievalFusionContext {
        recalls = recalls == null ? List.of() : List.copyOf(recalls);
        topK = Math.max(1, topK);
    }
}
