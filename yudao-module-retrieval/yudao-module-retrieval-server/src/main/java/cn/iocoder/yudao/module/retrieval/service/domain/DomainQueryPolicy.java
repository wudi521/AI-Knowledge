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

    /** 领域固定意图集(非空 = FIXED_DOMAIN 模式: 意图分类只用领域意图, 不受 KB 动态意图影响;
     *  null/空 = 走 KB 动态意图或代码默认)。意图钳制按此集合, 不匹配 → OUT_OF_SCOPE */
    List<String> supportedIntents();

    /** 是否启用产品/品牌一致性门禁(GENERAL=true, PATENT=false) */
    boolean enableProductGate();

    /** 是否启用通用槽位检测(GENERAL=true, PATENT=false) */
    boolean enableSlotDetection();

    /** 是否启用发布后的客服式意图自动总结(GENERAL=true, PATENT=false——专利意图由领域固定集提供) */
    boolean enableAutoIntentSummary();
}
