package cn.iocoder.yudao.module.retrieval.service.search.fusion;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

/** 领域可替换的 Fusion 插件 SPI；每个领域选择一个融合策略。 */
public interface RetrievalFusionPlugin extends DomainPipelinePlugin {

    RetrievalFusionResult fuse(RetrievalFusionContext context);
}
