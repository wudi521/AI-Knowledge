package cn.iocoder.yudao.module.retrieval.service.domain;

import cn.iocoder.yudao.framework.common.plugin.DomainPipelinePlugin;

import java.util.List;
import java.util.Set;

/**
 * 领域查询分析策略插件。
 *
 * <p>这是旧 QueryAnalysis 兼容链的领域 SPI，也统一使用平台 DomainPipelinePlugin 协议；
 * 新领域不再需要理解另一套私有 Registry 规则。</p>
 */
public interface DomainQueryPolicy extends DomainPipelinePlugin {

    /** 领域代码，例如 GENERAL/PATENT。 */
    String domainCode();

    @Override
    default String pluginId() {
        return "retrieval-query-policy:" + domainCode();
    }

    @Override
    default Set<String> supportedDomains() {
        return "GENERAL".equalsIgnoreCase(domainCode()) ? Set.of("*") : Set.of(domainCode());
    }

    /** 领域查询分析提示词(JSON 输出与 QueryAnalysis 兼容; null = 用代码默认提示词) */
    String queryAnalysisPrompt();

    /** 是否启用产品/品牌一致性门禁(GENERAL=true, PATENT=false) */
    boolean enableProductGate();

    /** 是否启用通用槽位检测(GENERAL=true, PATENT=false) */
    boolean enableSlotDetection();

    /**
     * 是否允许知识库动态意图覆盖领域意图。
     * GENERAL 保持历史行为; PATENT 等专业领域应返回 false, 防止客服类自动意图污染领域路由。
     */
    default boolean useKnowledgeBaseIntents() {
        return true;
    }

    /** 领域固定意图白名单。为空表示不做领域级钳制。 */
    default List<String> supportedIntents() {
        return List.of();
    }

    /** 是否启用发布后的客服式意图自动总结。 */
    default boolean enableAutoIntentSummary() {
        return true;
    }
}
