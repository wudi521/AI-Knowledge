package cn.iocoder.yudao.module.retrieval.service.search.recall;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

/**
 * 检索召回插件 SPI。
 *
 * <p>新增召回能力只需注册 Spring Bean，例如 BM25、Vector、条款号、申请号、图谱邻接等；
 * PlannedSearchService 不再为新领域增加分支。</p>
 */
public interface RetrievalRecallPlugin extends DomainPipelinePlugin {

    /** 稳定通道名，会进入 trace 和结果 channels。 */
    String channel();

    RetrievalRecallResult recall(RetrievalRecallContext context);
}
