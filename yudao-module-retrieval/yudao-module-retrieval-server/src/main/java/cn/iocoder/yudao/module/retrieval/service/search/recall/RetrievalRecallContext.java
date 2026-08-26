package cn.iocoder.yudao.module.retrieval.service.search.recall;

import java.util.List;

/**
 * Recall Pipeline 的强类型输入。这里只描述一次确定性召回需要的作用域，不承载自然语言 intent。
 */
public record RetrievalRecallContext(String query,
                                     List<String> variants,
                                     Long tenantId,
                                     List<Long> kbIds,
                                     List<Long> documentIds,
                                     int topK,
                                     String domainCode) {
    public RetrievalRecallContext {
        variants = variants == null ? List.of() : List.copyOf(variants);
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        topK = Math.max(1, topK);
    }
}
