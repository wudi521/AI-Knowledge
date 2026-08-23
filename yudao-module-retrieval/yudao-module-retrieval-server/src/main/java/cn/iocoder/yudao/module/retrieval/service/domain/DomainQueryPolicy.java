package cn.iocoder.yudao.module.retrieval.service.domain;

import java.util.List;

/**
 * 领域查询策略(轻量领域扩展点): 按知识库 domainCode 路由检索行为。
 * 领域实现通过 Spring Bean 注册, Registry 索引; 未找到回退 GENERAL。
 */
public interface DomainQueryPolicy {

    /** 领域代码: GENERAL/PATENT */
    String domainCode();

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

    /**
     * 领域固定意图白名单。为空表示不做领域级钳制, 继续使用知识库动态意图或默认枚举。
     */
    default List<String> supportedIntents() {
        return List.of();
    }
    /** 是否启用发布后的客服式意图自动总结(GENERAL=true, PATENT=false——专利意图由领域固定集提供) */
    default boolean enableAutoIntentSummary() {
        return true;
    }
}
