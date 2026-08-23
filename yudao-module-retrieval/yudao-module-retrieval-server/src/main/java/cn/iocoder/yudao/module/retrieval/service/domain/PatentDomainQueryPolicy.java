package cn.iocoder.yudao.module.retrieval.service.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 专利领域查询策略: 专利查询分析提示词 + 关闭产品门禁/通用槽位/客服动态意图。
 */
@Component
public class PatentDomainQueryPolicy implements DomainQueryPolicy {

    private static final List<String> SUPPORTED_INTENTS = List.of(
            "BIBLIOGRAPHIC_LOOKUP",
            "ABSTRACT_LOOKUP",
            "CLAIM_LOOKUP",
            "CLAIM_DEPENDENCY",
            "TECHNICAL_SOLUTION",
            "BACKGROUND_LOOKUP",
            "EMBODIMENT_LOOKUP",
            "DOCUMENT_COMPARISON",
            "OUT_OF_SCOPE",
            "OTHER"
    );

    /** 专利查询分析提示词(JSON 输出与 QueryAnalysis 兼容) */
    private static final String PATENT_QUERY_ANALYSIS_PROMPT = """
            你是专利公开文献知识库的"查询分析器"。当前知识库领域固定为 PATENT，禁止输出客服、合同、售后、保修、收费等非专利意图。
            给定问题(可能附带历史对话), 只输出合法 JSON:
            {"intent":"BIBLIOGRAPHIC_LOOKUP/ABSTRACT_LOOKUP/CLAIM_LOOKUP/CLAIM_DEPENDENCY/TECHNICAL_SOLUTION/BACKGROUND_LOOKUP/EMBODIMENT_LOOKUP/DOCUMENT_COMPARISON/OUT_OF_SCOPE/OTHER",
             "entities":["申请号/公布号/专利名称/申请人/发明人/权利要求号/章节等关键实体"],
             "products":[],
             "province":null,
             "city":null,
             "rewrites":["2~3条用于召回的改写"],
             "sub_questions":[]}

            【意图判定】
            - 问申请号、公布号、申请人、发明人、名称、IPC、申请日、公布日、权利要求数量 → BIBLIOGRAPHIC_LOOKUP
            - 问摘要 → ABSTRACT_LOOKUP
            - 问某项权利要求的内容、限定、保护对象 → CLAIM_LOOKUP
            - 问引用关系、从属关系、独立/从属权利要求 → CLAIM_DEPENDENCY
            - 问技术方案、核心方案、原理、解决什么技术问题 → TECHNICAL_SOLUTION
            - 问背景技术 → BACKGROUND_LOOKUP
            - 问具体实施方式、实施例 → EMBODIMENT_LOOKUP
            - 问哪一份文档提出某方案、比较多篇专利、跨文档定位 → DOCUMENT_COMPARISON
            - 明显与专利公开文献无关 → OUT_OF_SCOPE
            - 其他专利问题 → OTHER

            【精确标识保留】
            1. 申请号(如 202311042981.1)、公布号(如 CN 122604134 A)、权利要求号必须原样保留。
            2. rewrites 不得删掉用户明确给出的申请号/公布号/权利要求号。
            3. 若问题含“权利要求1主要限定什么”，intent 必须为 CLAIM_LOOKUP。
            4. 若问题含“权利要求8引用哪些权利要求”，intent 必须为 CLAIM_DEPENDENCY。
            5. 若问题是“哪一份文档提出/涉及……”，intent 优先为 DOCUMENT_COMPARISON。

            【上下文】
            有历史时仅用于消解“该专利/它/该权利要求”等指代；无关历史不得污染当前问题。
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
    public boolean enableProductGate() {
        return false;
    }

    @Override
    public boolean enableSlotDetection() {
        return false;
    }

    @Override
    public boolean useKnowledgeBaseIntents() {
        return false;
    }

    @Override
    public List<String> supportedIntents() {
        return SUPPORTED_INTENTS;
    }
}
