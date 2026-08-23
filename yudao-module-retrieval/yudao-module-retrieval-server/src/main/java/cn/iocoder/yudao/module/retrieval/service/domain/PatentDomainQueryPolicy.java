package cn.iocoder.yudao.module.retrieval.service.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 专利领域查询策略: 专利查询分析提示词 + 固定领域意图集(FIXED_DOMAIN, 不受 KB 动态意图影响)
 * + 关闭产品门禁/通用槽位检测/客服式意图自动总结
 */
@Component
public class PatentDomainQueryPolicy implements DomainQueryPolicy {

    /** 专利领域固定意图集(意图钳制/路由/证据策略均按此; 不匹配 → OUT_OF_SCOPE) */
    public static final List<String> PATENT_INTENTS = List.of(
            "BIBLIOGRAPHIC_LOOKUP",   // 著录信息(申请号/公布号/申请人/发明人/名称/IPC)
            "ABSTRACT_LOOKUP",        // 摘要
            "CLAIM_LOOKUP",           // 权利要求内容/限定
            "CLAIM_DEPENDENCY",       // 权利要求引用/从属/独立
            "TECHNICAL_SOLUTION",     // 技术方案/原理
            "BACKGROUND_LOOKUP",      // 背景技术
            "EMBODIMENT_LOOKUP",      // 具体实施方式
            "DOCUMENT_COMPARISON",    // 多文档对比
            "OUT_OF_SCOPE",           // 与专利公开文献无关
            "OTHER");

    /** 专利查询分析提示词(JSON 输出与 QueryAnalysis 兼容; 意图枚举见任务书 8.2) */
    private static final String PATENT_QUERY_ANALYSIS_PROMPT = """
            你是专利公开文献知识库的"查询分析器"。给定问题(可能附带历史对话), 输出 JSON:
            {"intent": "意图分类(BIBLIOGRAPHIC_LOOKUP/ABSTRACT_LOOKUP/CLAIM_LOOKUP/CLAIM_DEPENDENCY/TECHNICAL_SOLUTION/BACKGROUND_LOOKUP/EMBODIMENT_LOOKUP/DOCUMENT_COMPARISON/OUT_OF_SCOPE/OTHER)",
             "entities": ["关键实体: 申请号/公布号/专利名称/申请人/发明人/权利要求号/章节"],
             "products": [],
             "province": null,
             "city": null,
             "rewrites": ["2~3条改写变体"],
             "sub_questions": []}
            只输出合法 JSON, 不要其他文字。

            【精确编号保留】改写变体必须原样保留申请号(如 202311344028.2)、公布号(如 CN 122621758 A)、
            权利要求号(如 权利要求1/权利要求8), 不得丢失小数点、空格或字母后缀。

            【意图判定】
            - 问申请号/公布号/申请人/发明人/名称/IPC → BIBLIOGRAPHIC_LOOKUP
            - 问摘要 → ABSTRACT_LOOKUP
            - 问某项权利要求内容/限定 → CLAIM_LOOKUP
            - 问权利要求引用关系/从属/独立 → CLAIM_DEPENDENCY
            - 问技术方案/原理 → TECHNICAL_SOLUTION
            - 问背景技术 → BACKGROUND_LOOKUP
            - 问具体实施方式 → EMBODIMENT_LOOKUP
            - 多篇文档对比 → DOCUMENT_COMPARISON
            - 与专利公开文献无关 → OUT_OF_SCOPE
            - 其他 → OTHER

            【上下文消歧(仅当输入含"历史对话"时执行)】同通用规则: 指代展开/实体继承。
            """;

    @Override
    public String domainCode() {
        return "PATENT";
    }

    @Override
    public String queryAnalysisPrompt() {
        return PATENT_QUERY_ANALYSIS_PROMPT;
    }

    @Override
    public List<String> supportedIntents() {
        return PATENT_INTENTS;
    }

    @Override
    public boolean enableProductGate() {
        return false; // 专利领域无产品/品牌一致性门禁
    }

    @Override
    public boolean enableSlotDetection() {
        return false; // 专利 MVP 关闭通用客服槽位反问
    }

    @Override
    public boolean enableAutoIntentSummary() {
        return false; // 专利意图由领域固定集提供, 禁止客服式自动总结覆盖
    }
}
