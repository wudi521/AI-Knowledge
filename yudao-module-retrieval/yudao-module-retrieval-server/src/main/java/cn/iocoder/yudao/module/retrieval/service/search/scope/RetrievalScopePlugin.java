package cn.iocoder.yudao.module.retrieval.service.search.scope;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

/**
 * 检索 hard-scope 插件 SPI。
 *
 * <p>典型用途：专利申请号、合同编号、地区、产品线、法规版本等确定性范围约束。
 * 插件只负责收窄候选范围，不能扩大上游已经确认的 documentIds。</p>
 */
public interface RetrievalScopePlugin extends DomainPipelinePlugin {

    RetrievalScopeDecision refine(RetrievalScopeContext context);
}
