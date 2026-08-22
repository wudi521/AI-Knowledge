package cn.iocoder.yudao.module.retrieval.service.domain;

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
}
