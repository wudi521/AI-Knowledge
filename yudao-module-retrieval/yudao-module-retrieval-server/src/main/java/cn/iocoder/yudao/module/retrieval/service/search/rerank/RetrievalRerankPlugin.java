package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

/** 领域可替换的 Rerank 插件 SPI；每个领域选择一个重排策略。 */
public interface RetrievalRerankPlugin extends DomainPipelinePlugin {

    RetrievalRerankResult rerank(RetrievalRerankContext context);
}
